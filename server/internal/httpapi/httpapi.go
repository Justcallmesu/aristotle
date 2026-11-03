// Package httpapi holds the HTTP routers and handlers.
package httpapi

import (
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

type healthResponse struct {
	Status  string `json:"status"`
	Version string `json:"version"`
}

type errorResponse struct {
	Error string `json:"error"`
}

// Deps are what the phone-facing routes need.
type Deps struct {
	Codes   *pairing.Codes
	Devices *devices.Store
}

// NewRouter returns the loopback handler: health only.
func NewRouter(version string, log *slog.Logger) http.Handler {
	return logRequests(log, newMux(version))
}

// NewRemoteRouter returns the handler for the LAN and Tailscale listeners.
// Every /v1/* route needs a device token except POST /v1/pair.
func NewRemoteRouter(version string, log *slog.Logger, d Deps) http.Handler {
	mux := newMux(version)
	mux.Handle("POST /v1/pair", pairHandler(d, log, newRejectLog(log)))
	mux.Handle("GET /v1/me", requireDevice(d.Devices, http.HandlerFunc(meHandler)))
	mux.Handle("/v1/", requireDevice(d.Devices, http.NotFoundHandler()))
	return logRequests(log, mux)
}

func newMux(version string) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", healthHandler(version))
	return mux
}

func healthHandler(version string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, healthResponse{Status: "ok", Version: version})
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, errorResponse{Error: msg})
}
