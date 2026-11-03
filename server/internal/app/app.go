// Package app wires configuration, logging and the HTTP server together.
package app

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/config"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
)

const shutdownTimeout = 5 * time.Second

const usage = `usage: aristotle-server <command>

commands:
  serve    run the server (loopback only)
  version  print the version
  help     show this help
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

// Serve loads config, prepares the state dir and serves until ctx is done.
func Serve(ctx context.Context, version string, log *slog.Logger) error {
	cfgPath, err := config.ConfigPath()
	if err != nil {
		return err
	}
	cfg, err := config.Load(cfgPath)
	if err != nil {
		return err
	}
	stateDir, err := config.EnsureStateDir()
	if err != nil {
		return err
	}
	ln, err := net.Listen("tcp", cfg.Addr())
	if err != nil {
		return fmt.Errorf("listen %s: %w", cfg.Addr(), err)
	}
	log.Info("listening", "addr", ln.Addr().String(), "state_dir", stateDir, "version", version)
	return serveListener(ctx, ln, httpapi.NewRouter(version, log), log)
}

func serveListener(ctx context.Context, ln net.Listener, h http.Handler, log *slog.Logger) error {
	srv := &http.Server{Handler: h, ReadHeaderTimeout: 10 * time.Second}
	errc := make(chan error, 1)
	go func() { errc <- srv.Serve(ln) }()
	select {
	case err := <-errc:
		return err
	case <-ctx.Done():
	}
	log.Info("shutting down")
	sctx, cancel := context.WithTimeout(context.Background(), shutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(sctx); err != nil {
		return fmt.Errorf("shutdown: %w", err)
	}
	if err := <-errc; err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
