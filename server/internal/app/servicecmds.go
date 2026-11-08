package app

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/justcallmesu/aristotle/server/internal/config"
	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/service"
)

// Seams for tests: the command runner, the running binary's path and stdin.
var (
	serviceRunner service.Runner = service.ExecRunner{}
	executable                   = func() (string, error) {
		p, err := os.Executable()
		if err != nil {
			return "", err
		}
		return filepath.EvalSymlinks(p)
	}
	stdin io.Reader = os.Stdin
)

func newManager(stdout, stderr io.Writer) (*service.Manager, error) {
	dir, err := service.UnitDir()
	if err != nil {
		return nil, err
	}
	bin, err := executable()
	if err != nil {
		return nil, err
	}
	return &service.Manager{Dir: dir, Bin: bin, Runner: serviceRunner, Out: stdout, Err: stderr}, nil
}

func runInstall(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("install", flag.ContinueOnError)
	fs.SetOutput(stderr)
	start := fs.Bool("start", false, "start the service now as well")
	force := fs.Bool("force", false, "replace a different existing unit file")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() != 0 {
		fmt.Fprintln(stderr, "usage: aristotle-server install [--start] [--force]")
		return 2
	}
	m, err := newManager(stdout, stderr)
	if err == nil {
		err = m.Install(*force, *start)
	}
	if err == nil && *force && !*start && !m.Restarted {
		fmt.Fprintln(stdout, "The service was not running, so nothing was restarted.")
	}
	if err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	if m.Restarted {
		fmt.Fprintln(stdout, "Installed, enabled and restarted the running service: the new binary and unit are live.")
	} else if *start {
		fmt.Fprintln(stdout, "Installed, enabled and started.")
	} else {
		fmt.Fprintln(stdout, "Installed and enabled (starts at next login). Start it now with: systemctl --user start aristotle-server")
	}
	fmt.Fprintln(stdout, "To keep it running when you are logged out, run: loginctl enable-linger $USER")
	return 0
}

func runUninstall(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("uninstall", flag.ContinueOnError)
	fs.SetOutput(stderr)
	purge := fs.Bool("purge", false, "also delete the state directory (paired devices, certificate)")
	yes := fs.Bool("yes", false, "with --purge: do not ask for confirmation")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() != 0 || (*yes && !*purge) {
		fmt.Fprintln(stderr, "usage: aristotle-server uninstall [--purge [--yes]]")
		return 2
	}
	var stateDir string
	if *purge {
		dir, err := config.StateDir()
		if err != nil {
			fmt.Fprintln(stderr, "error:", err)
			return 1
		}
		stateDir = dir
		if !*yes && !confirm(stdout, fmt.Sprintf("Delete %s, including all paired devices and the certificate? [y/N] ", dir)) {
			fmt.Fprintln(stderr, "aborted; nothing changed")
			return 1
		}
	}
	m, err := newManager(stdout, stderr)
	if err == nil {
		err = m.Uninstall()
	}
	if err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	fmt.Fprintln(stdout, "Service removed.")
	if *purge {
		if err := os.RemoveAll(stateDir); err != nil {
			fmt.Fprintln(stderr, "error:", err)
			return 1
		}
		fmt.Fprintf(stdout, "Deleted %s.\n", stateDir)
	} else {
		fmt.Fprintln(stdout, "Paired devices and the certificate were kept (use --purge to delete them).")
	}
	return 0
}

func confirm(w io.Writer, prompt string) bool {
	fmt.Fprint(w, prompt)
	line, _ := bufio.NewReader(stdin).ReadString('\n')
	switch strings.ToLower(strings.TrimSpace(line)) {
	case "y", "yes":
		return true
	}
	return false
}

func runStatus(args []string, stdout, stderr io.Writer) int {
	if len(args) != 0 {
		fmt.Fprintln(stderr, "usage: aristotle-server status")
		return 2
	}
	m, err := newManager(stdout, stderr)
	if err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	active := m.State("is-active")
	fmt.Fprintf(stdout, "Service:  %s\n", active)
	fmt.Fprintf(stdout, "Enabled:  %s\n", m.State("is-enabled"))
	var res statusResult
	err = queryStatus(&res)
	answering := err == nil
	switch {
	case errors.Is(err, control.ErrNotRunning):
		fmt.Fprintln(stdout, "Server:   not running (no control socket)")
	case err != nil:
		fmt.Fprintf(stdout, "Server:   no answer (%v)\n", err)
	default:
		fmt.Fprintf(stdout, "Server:   running, version %s\n", sanitize(res.Version))
		fmt.Fprintln(stdout, "Addresses:")
		printAddrs(stdout, toPairAddrs(res.Addresses))
	}
	if active != "active" || !answering {
		return 1
	}
	return 0
}

// queryStatus asks the running server for its status (a seam for tests).
var queryStatus = func(res *statusResult) error { return callServer(cmdStatus, nil, res) }

func toPairAddrs(in []httpapi.Address) []pairAddr {
	out := make([]pairAddr, len(in))
	for i, a := range in {
		out[i] = pairAddr{Address: a}
	}
	return out
}

func runLogs(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("logs", flag.ContinueOnError)
	fs.SetOutput(stderr)
	follow := fs.Bool("f", false, "follow new log lines")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() != 0 {
		fmt.Fprintln(stderr, "usage: aristotle-server logs [-f]")
		return 2
	}
	m := &service.Manager{Runner: serviceRunner, Out: stdout, Err: stderr}
	if err := m.Logs(*follow); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	return 0
}
