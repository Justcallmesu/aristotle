package httpapi

import (
	"log/slog"
	"net/http"
)

// logRequests logs method, route pattern and status: never bodies, queries,
// headers or the raw path. An unmatched request logs route=unmatched, since
// a client-chosen path may hold a secret. A WebSocket upgrade (101) is logged
// when the upgrade is written, not when the long-lived socket ends.
func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		logged := false
		logLine := func(status int) {
			logged = true
			log.Info("request", "method", r.Method, "route", routeLabel(r), "status", status)
		}
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK, onSwitch: func() { logLine(http.StatusSwitchingProtocols) }}
		next.ServeHTTP(sw, r)
		if !logged {
			logLine(sw.status)
		}
	})
}

func routeLabel(r *http.Request) string {
	if r.Pattern != "" {
		return r.Pattern
	}
	return "unmatched"
}

type statusWriter struct {
	http.ResponseWriter
	status      int
	wroteHeader bool
	onSwitch    func() // runs once the 101 Switching Protocols header is written
}

func (w *statusWriter) WriteHeader(code int) {
	first := !w.wroteHeader
	if first {
		w.status, w.wroteHeader = code, true
	}
	w.ResponseWriter.WriteHeader(code)
	if first && code == http.StatusSwitchingProtocols && w.onSwitch != nil {
		w.onSwitch()
	}
}

// Unwrap lets http.ResponseController reach the underlying writer (flush,
// hijack for the T06 WebSocket).
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
