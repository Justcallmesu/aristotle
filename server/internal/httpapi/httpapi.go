// Package httpapi holds the HTTP router and handlers.
package httpapi

import (
	"encoding/json"
	"log/slog"
	"net/http"
)

type healthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

// NewRouter returns the server's handler.
func NewRouter(version string, log *slog.Logger) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthHandler(version))
	return logRequests(log, mux)
}

func healthHandler(version string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, healthResponse{Status: "ok", Version: version})
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// logRequests logs method and path only: never bodies, queries or headers.
func logRequests(log *slog.Logger, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Info("request", "method", r.Method, "path", r.URL.Path)
		next.ServeHTTP(w, r)
	})
}
