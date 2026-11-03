package httpapi

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealthz(t *testing.T) {
	h := NewRouter("v-test", slog.New(slog.NewTextHandler(io.Discard, nil)))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if rec.Code != 200 {
		t.Fatalf("code %d", rec.Code)
	}
	var got map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["status"] != "ok" || got["version"] != "v-test" {
		t.Fatalf("body %v", got)
	}
}

func TestMethodAndUnknownPath(t *testing.T) {
	h := NewRouter("x", slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, tt := range []struct {
		method, path string
		want         int
	}{
		{http.MethodPost, "/healthz", http.StatusMethodNotAllowed},
		{http.MethodGet, "/nope", http.StatusNotFound},
	} {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(tt.method, tt.path, nil))
		if rec.Code != tt.want {
			t.Errorf("%s %s = %d, want %d", tt.method, tt.path, rec.Code, tt.want)
		}
	}
}

func TestLogsOmitBodyAndAuth(t *testing.T) {
	var buf bytes.Buffer
	h := NewRouter("x", slog.New(slog.NewTextHandler(&buf, nil)))
	req := httptest.NewRequest(http.MethodPost, "/healthz?token=secretq", strings.NewReader("secret-body"))
	req.Header.Set("Authorization", "Bearer secret-token")
	h.ServeHTTP(httptest.NewRecorder(), req)
	if strings.Contains(buf.String(), "secret") {
		t.Fatalf("log leaked: %s", buf.String())
	}
}

func TestLogUsesRouteWithoutQuery(t *testing.T) {
	var buf bytes.Buffer
	h := NewRouter("x", slog.New(slog.NewTextHandler(&buf, nil)))
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest(http.MethodGet, "/healthz?a=b", nil))
	if !strings.Contains(buf.String(), "route=\"GET /healthz\"") || strings.Contains(buf.String(), "a=b") {
		t.Fatalf("log = %s", buf.String())
	}
}
