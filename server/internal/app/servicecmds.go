package app

import (
	"bufio"
	"bytes"
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
	"github.com/justcallmesu/aristotle/server/internal/listen"
	"github.com/justcallmesu/aristotle/server/internal/service"
	"github.com/justcallmesu/aristotle/server/internal/terminal"
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

	// The remote-terminal test of stop and restart reads this environment and cgroup file.
	getenv     = os.Getenv
	cgroupFile = "/proc/self/cgroup"
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
	args, asJSON := stripJSON(args)
	porcelain := len(args) == 1 && args[0] == "--porcelain"
	if (len(args) != 0 && !porcelain) || (asJSON && porcelain) {
		fmt.Fprintln(stderr, "usage: aristotle-server status [--porcelain | --json]")
		return 2
	}
	m, err := newManager(stdout, stderr)
	if err != nil {
		return failCmd(stdout, stderr, asJSON, err)
	}
	active, enabled := m.State("is-active"), m.State("is-enabled")
	var res statusResult
	err = queryStatus(&res)
	switch {
	case asJSON:
		printJSON(stdout, newStatusJSON(active, enabled, m.Installed(), res, err))
	case porcelain:
		printPorcelain(stdout, active, enabled, res, err)
	default:
		printStatus(stdout, active, enabled, res, err)
	}
	if active != "active" || err != nil {
		return 1
	}
	return 0
}

// statusJSON is `status --json`: the service facts from systemd, whether the unit file exists, how the server answered (the word
// `status --porcelain` prints after "server"), and the control result when it did.
type statusJSON struct {
	Service   string        `json:"service"`
	Enabled   string        `json:"enabled"`
	Installed bool          `json:"installed"`
	Server    string        `json:"server"`
	Result    *statusResult `json:"result,omitempty"`
}

func newStatusJSON(active, enabled string, installed bool, res statusResult, err error) statusJSON {
	out := statusJSON{Service: active, Enabled: enabled, Installed: installed, Server: serverState(err)}
	if err == nil {
		res.Addresses = nonNil(res.Addresses)
		out.Result = &res
	}
	return out
}

// serverState is how the server answered the status query: the word after "server" in --porcelain.
func serverState(err error) string {
	switch {
	case errors.Is(err, control.ErrNotRunning):
		return "not-running"
	case err != nil:
		return "no-answer"
	}
	return "running"
}

func printStatus(w io.Writer, active, enabled string, res statusResult, err error) {
	fmt.Fprintf(w, "Service:  %s\n", active)
	fmt.Fprintf(w, "Enabled:  %s\n", enabled)
	switch {
	case errors.Is(err, control.ErrNotRunning):
		fmt.Fprintln(w, "Server:   not running (no control socket)")
	case err != nil:
		fmt.Fprintf(w, "Server:   no answer (%v)\n", err)
	default:
		fmt.Fprintf(w, "Server:   running, version %s\n", sanitize(res.Version))
		fmt.Fprintln(w, "Addresses:")
		printAddrs(w, toPairAddrs(res.Addresses))
		printHermesLine(w, res.Hermes)
	}
}

func printHermesLine(w io.Writer, h *hermesStatus) {
	if h == nil {
		return
	}
	switch h.State {
	case hermesReachable:
		fmt.Fprintln(w, "Hermes:   reachable")
	case hermesNotRunning:
		fmt.Fprintf(w, "Hermes:   not running (%s)\n", sanitize(h.Reason))
	default:
		fmt.Fprintf(w, "Hermes:   error (%s)\n", sanitize(h.Reason))
	}
}

// printPorcelain writes the stable one-fact-per-line form the installer parses.
func printPorcelain(w io.Writer, active, enabled string, res statusResult, err error) {
	fmt.Fprintf(w, "service %s\n", porcelainWord(active))
	fmt.Fprintf(w, "enabled %s\n", porcelainWord(enabled))
	state := serverState(err)
	fmt.Fprintf(w, "server %s\n", state)
	if err != nil {
		return
	}
	fmt.Fprintf(w, "version %s\n", porcelainWord(res.Version))
	if res.Port > 0 {
		fmt.Fprintf(w, "port %d\n", res.Port)
	}
	for _, l := range res.Listeners {
		switch l.Kind {
		case string(listen.LAN):
			fmt.Fprintf(w, "lan %s %s %s\n", porcelainWord(l.Iface), porcelainWord(l.IP), porcelainWord(l.Subnet))
		case string(listen.Tailscale):
			fmt.Fprintf(w, "tailscale %s %s\n", porcelainWord(l.Iface), porcelainWord(l.IP))
		}
	}
	if res.Hermes != nil {
		fmt.Fprintf(w, "hermes %s\n", porcelainWord(res.Hermes.State))
	}
}

// porcelainWord keeps a value one token: sanitized, no spaces.
func porcelainWord(s string) string {
	return strings.Join(strings.Fields(sanitize(s)), "_")
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

// lifecycle maps the start, stop and restart commands to their Manager method.
var lifecycle = map[string]func(*service.Manager) error{
	"start":   (*service.Manager).Start,
	"stop":    (*service.Manager).Stop,
	"restart": (*service.Manager).Restart,
}

// lifecycleJSON is `start|stop|restart --json`: the service state after the verb ran.
type lifecycleJSON struct {
	OK      bool   `json:"ok"`
	Service string `json:"service"`
}

// runLifecycle runs `systemctl --user <verb> aristotle-server.service`. stop and restart refuse inside an Aristotle
// remote terminal: the shell would die with the service mid-command. start is allowed.
func runLifecycle(verb string, args []string, stdout, stderr io.Writer) int {
	args, asJSON := stripJSON(args)
	if len(args) != 0 {
		fmt.Fprintf(stderr, "usage: aristotle-server %s [--json]\n", verb)
		return 2
	}
	if verb != "start" && service.InRemoteTerminal(terminal.MarkerEnv, getenv, cgroupFile) {
		return failCmd(stdout, stderr, asJSON, fmt.Errorf("refused: %s cannot be run from an Aristotle remote terminal "+
			"(it would end this shell mid-command); run it in a terminal opened on the PC itself", verb))
	}
	var detail bytes.Buffer
	m := &service.Manager{Runner: serviceRunner, Out: io.Discard, Err: &detail}
	if err := lifecycle[verb](m); err != nil {
		if msg := strings.TrimSpace(detail.String()); msg != "" {
			err = fmt.Errorf("systemctl --user %s: %w: %s", verb, err, msg)
		} else {
			err = fmt.Errorf("systemctl --user %s: %w", verb, err)
		}
		return failCmd(stdout, stderr, asJSON, err)
	}
	active := m.State("is-active")
	if asJSON {
		return emitJSON(stdout, lifecycleJSON{OK: true, Service: active})
	}
	fmt.Fprintf(stdout, "Service %s done; it is now %s.\n", verb, active)
	return 0
}
