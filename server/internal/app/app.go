// Package app wires configuration, logging and the servers together and
// implements the aristotle-server subcommands.
package app

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
)

const usage = `usage: aristotle-server <command>

commands:
  serve                 run the server (loopback health check; LAN and Tailscale over HTTPS)
  pair [--name NAME] [--no-qr]
                        get a one-time pairing code (and its QR code) from the running server
  devices list          list paired devices
  devices revoke <id>   revoke a paired device
  install [--start] [--force]
                        install as a systemd user service (starts at login)
  uninstall [--purge [--yes]]
                        remove the service; --purge also deletes devices and certificate
  status                service state and the listener URLs
  logs [-f]             show the service log (journalctl)
  version               print the version
  help                  show this help
`

// Run dispatches a subcommand and returns the process exit code.
func Run(args []string, version string, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stderr, usage)
		return 2
	}
	switch args[0] {
	case "version":
		fmt.Fprintln(stdout, version)
		return 0
	case "help", "-h", "--help":
		fmt.Fprint(stdout, usage)
		return 0
	case "serve":
		return runServe(version, stderr)
	case "pair":
		return runPair(args[1:], stdout, stderr)
	case "devices":
		return runDevices(args[1:], stdout, stderr)
	case "install":
		return runInstall(args[1:], stdout, stderr)
	case "uninstall":
		return runUninstall(args[1:], stdout, stderr)
	case "status":
		return runStatus(args[1:], stdout, stderr)
	case "logs":
		return runLogs(args[1:], stdout, stderr)
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", args[0], usage)
		return 2
	}
}

func runServe(version string, stderr io.Writer) int {
	log := slog.New(slog.NewTextHandler(stderr, nil))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	sigs := make(chan os.Signal, 2)
	signal.Notify(sigs, os.Interrupt, syscall.SIGTERM)
	defer signal.Stop(sigs)
	go watchSignals(sigs, cancel, os.Exit)
	if err := Serve(ctx, version, log); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	return 0
}

// exitInterrupted is the exit code (128+SIGINT) when a second signal cuts a
// graceful shutdown short.
const exitInterrupted = 130

// watchSignals cancels on the first signal and exits at once on the second.
func watchSignals(sigs <-chan os.Signal, cancel context.CancelFunc, exit func(int)) {
	<-sigs
	cancel()
	<-sigs
	exit(exitInterrupted)
}
