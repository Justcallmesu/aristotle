package httpapi

import (
	"log/slog"
	"net/http"
)

// logRequests logs method, route pattern and status: never bodies, queries,
// headers or the raw path. An unmatched request logs route=unmatched, since
// a client-chosen path may hold a secret.
func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		log.Info("request", "method", r.Method, "route", routeLabel(r), "status", sw.status)
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
}

func (w *statusWriter) WriteHeader(code int) {
	if !w.wroteHeader {
		w.status, w.wroteHeader = code, true
	}
	w.ResponseWriter.WriteHeader(code)
}

// Unwrap lets http.ResponseController reach the underlying writer (flush,
// hijack for the T06 WebSocket).
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }
