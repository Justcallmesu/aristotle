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
	// Rejects limits the "pairing attempts rejected" log line; share one across listeners.
	Rejects *RejectLog
	// Relay serves GET /v1/ws; nil leaves the route out (404 with a valid token).
	Relay RelayServer
	// Listener is the listener kind ("lan", "tailscale") for the relay's audit line.
	Listener string
	// Addresses lists the listeners bound right now, for the pair response and GET /v1/addresses;
	// nil means none.
	Addresses func() []Address
}

// Address is one listener URL the phone can use (docs/protocol.md §11.2). Every URL is https://.
type Address struct {
	URL   string `json:"url"`
	Kind  string `json:"kind"` // "lan" or "tailscale"
	Iface string `json:"iface"`
}

type addressesResponse struct {
	Addresses []Address `json:"addresses"`
}

// addresses never returns nil, so the JSON is always an array.
func (d Deps) addresses() []Address {
	if d.Addresses == nil {
		return []Address{}
	}
	if list := d.Addresses(); list != nil {
		return list
	}
	return []Address{}
}

// RelayServer upgrades an authenticated /v1/ws request and relays it to Hermes (package relay).
type RelayServer interface {
	ServeRelay(w http.ResponseWriter, r *http.Request, deviceID, listener string)
}

// NewRouter returns the loopback handler: health only.
func NewRouter(version string, log *slog.Logger) http.Handler {
	return logRequests(log, newMux(version))
}

// NewRemoteRouter returns the handler for the LAN and Tailscale listeners.
// Every /v1/* route needs a device token except POST /v1/pair.
func NewRemoteRouter(version string, log *slog.Logger, d Deps) http.Handler {
	mux := newMux(version)
	if d.Rejects == nil {
		d.Rejects = NewRejectLog(log)
	}
	mux.Handle("POST /v1/pair", pairHandler(d, log, d.Rejects))
	mux.Handle("GET /v1/me", requireDevice(d.Devices, http.HandlerFunc(meHandler)))
	mux.Handle("GET /v1/addresses", requireDevice(d.Devices, addressesHandler(d)))
	if d.Relay != nil {
		mux.Handle("GET /v1/ws", requireDevice(d.Devices, relayHandler(d.Relay, d.Listener)))
	}
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

// addressesHandler lists the current listeners, so a paired phone learns new addresses (DHCP, Tailscale
// turned on) without pairing again.
func addressesHandler(d Deps) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, addressesResponse{Addresses: d.addresses()})
	})
}

func relayHandler(relay RelayServer, listener string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dev, _ := DeviceFrom(r.Context())
		relay.ServeRelay(w, r, dev.ID, listener)
	})
}
