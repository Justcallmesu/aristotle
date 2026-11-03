package httpapi

import (
	"context"
	"net/http"
	"strings"

	"github.com/justcallmesu/aristotle/server/internal/devices"
)

type deviceKey struct{}

// requireDevice lets the request through only with a valid
// "Authorization: Bearer <token>" for a non-revoked device.
func requireDevice(store *devices.Store, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		dev, ok := store.Authenticate(bearerToken(r))
		if !ok {
			w.Header().Set("WWW-Authenticate", "Bearer")
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), deviceKey{}, dev)))
	})
}

// bearerToken returns the token of a Bearer Authorization header, or "".
func bearerToken(r *http.Request) string {
	scheme, token, ok := strings.Cut(r.Header.Get("Authorization"), " ")
	if !ok || !strings.EqualFold(scheme, "Bearer") {
		return ""
	}
	return strings.TrimSpace(token)
}

// DeviceFrom returns the authenticated device of a request.
func DeviceFrom(ctx context.Context) (devices.Public, bool) {
	d, ok := ctx.Value(deviceKey{}).(devices.Public)
	return d, ok
}

type meResponse struct {
	DeviceID string `json:"device_id"`
	Name     string `json:"name"`
}

func meHandler(w http.ResponseWriter, r *http.Request) {
	dev, _ := DeviceFrom(r.Context())
	writeJSON(w, http.StatusOK, meResponse{DeviceID: dev.ID, Name: dev.Name})
}
