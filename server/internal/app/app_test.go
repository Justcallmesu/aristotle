package app

import (
	"bytes"
	"context"
	"io"
	"log/slog"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/httpapi"
)

func TestRunDispatch(t *testing.T) {
	tests := []struct {
		name       string
		args       []string
		wantCode   int
		wantOut    string
		wantErrOut string
	}{
		{"no args", nil, 2, "", "usage:"},
		{"unknown", []string{"bogus"}, 2, "", "unknown command"},
		{"version", []string{"version"}, 0, "1.2.3\n", ""},
		{"help", []string{"help"}, 0, "usage:", ""},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var out, errOut bytes.Buffer
			code := Run(tt.args, "1.2.3", &out, &errOut)
			if code != tt.wantCode {
				t.Fatalf("code %d, want %d", code, tt.wantCode)
			}
			if !strings.Contains(out.String(), tt.wantOut) || !strings.Contains(errOut.String(), tt.wantErrOut) {
				t.Fatalf("out=%q err=%q", out.String(), errOut.String())
			}
		})
	}
}

func TestServeListenerGracefulShutdown(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- serveListener(ctx, ln, httpapi.NewRouter("t", log), log) }()

	resp, err := http.Get("http://" + ln.Addr().String() + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("status %d", resp.StatusCode)
	}
	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("shutdown timed out")
	}
}
