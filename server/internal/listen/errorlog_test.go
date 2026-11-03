package listen

import (
	"log/slog"
	"net/http"
	"strings"
	"testing"
	"time"
)

// D30: net/http's own log lines reach slog: a TLS handshake failure at Debug, anything else at Warn.
func TestErrorLogLevels(t *testing.T) {
	var buf syncBuffer
	log := ErrorLog(slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug})))
	log.Printf("http: TLS handshake error from 192.168.1.9:51234: EOF")
	log.Printf("http: panic serving 192.168.1.9:51234: boom")
	lines := strings.Split(strings.TrimSpace(buf.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("lines = %q", lines)
	}
	if !strings.Contains(lines[0], "level=DEBUG") || !strings.Contains(lines[0], "TLS handshake error") {
		t.Fatalf("handshake line %q, want level=DEBUG", lines[0])
	}
	if !strings.Contains(lines[1], "level=WARN") || !strings.Contains(lines[1], "boom") {
		t.Fatalf("panic line %q, want level=WARN", lines[1])
	}
}

// At the default level (Info) the routine handshake noise is dropped and a warning is kept.
func TestErrorLogDropsHandshakeNoiseAtInfo(t *testing.T) {
	var buf syncBuffer
	log := ErrorLog(slog.New(slog.NewTextHandler(&buf, nil)))
	log.Printf("http: TLS handshake error from 100.64.0.9:1: tls: first record does not look like a TLS handshake")
	log.Printf("http: Accept error: too many open files; retrying in 5ms")
	got := buf.String()
	if strings.Contains(got, "handshake") || !strings.Contains(got, "level=WARN") || !strings.Contains(got, "Accept error") {
		t.Fatalf("got %q", got)
	}
}

// D30: the LAN/Tailscale servers log through the manager's slog logger, not the stdlib default logger.
func TestManagerServerLogsThroughSlog(t *testing.T) {
	m, bound := tailnetManager(t, http.NotFoundHandler())
	var buf syncBuffer
	m.Log = slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	m.Reconcile()
	defer m.Close()
	resp, err := http.Get("http://" + bound() + "/") // plain HTTP: a TLS handshake error
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	deadline := time.Now().Add(2 * time.Second)
	for !strings.Contains(buf.String(), tlsHandshakePrefix) && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	got := buf.String()
	if !strings.Contains(got, tlsHandshakePrefix) || !strings.Contains(got, "level=DEBUG") {
		t.Fatalf("handshake error not logged via slog at Debug: %q", got)
	}
}
