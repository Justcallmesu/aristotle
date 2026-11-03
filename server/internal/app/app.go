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
  serve                 run the server (loopback, LAN over HTTPS, Tailscale over HTTP)
  pair [--name NAME]    get a one-time pairing code from the running server
  devices list          list paired devices
  devices revoke <id>   revoke a paired device
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
	default:
		fmt.Fprintf(stderr, "unknown command %q\n\n%s", args[0], usage)
		return 2
	}
}

func runServe(version string, stderr io.Writer) int {
	log := slog.New(slog.NewTextHandler(stderr, nil))
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := Serve(ctx, version, log); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	return 0
}
