package listen

import (
	"context"
	stdlog "log"
	"log/slog"
	"strings"
	"time"
)

// tlsHandshakePrefix starts the line net/http writes for every failed TLS handshake
// (net/http/server.go, conn.serve): port scans, plain-HTTP requests, a phone that dropped mid-handshake.
const tlsHandshakePrefix = "http: TLS handshake error"

// ErrorLog bridges net/http's own error log (a handler panic, an accept error, a TLS handshake
// failure) into slog, one bridge for every listener: loopback, LAN and Tailscale (D30). A TLS
// handshake failure is routine on a LAN or tailnet port and goes out at Debug; every other line
// at Warn, so a panic is never hidden below the default level.
func ErrorLog(log *slog.Logger) *stdlog.Logger {
	return stdlog.New(errorLogWriter{log.Handler()}, "", 0)
}

type errorLogWriter struct{ handler slog.Handler }

func (w errorLogWriter) Write(p []byte) (int, error) {
	msg := strings.TrimRight(string(p), "\n")
	level := slog.LevelWarn
	if strings.HasPrefix(msg, tlsHandshakePrefix) {
		level = slog.LevelDebug
	}
	ctx := context.Background()
	if w.handler.Enabled(ctx, level) {
		_ = w.handler.Handle(ctx, slog.NewRecord(time.Now(), level, msg, 0))
	}
	return len(p), nil
}
