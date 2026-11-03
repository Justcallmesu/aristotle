package listen

import (
	"net/http"
	"net/netip"
)

// PeerGate lets a request through only when the peer may use this listener:
//   - LAN listener: an RFC1918 peer inside the listener interface's own
//     subnet (on-link), so a container on a docker bridge or a host behind a
//     router cannot reach the Wi-Fi listener;
//   - Tailscale listener: a tailnet peer.
//
// Anything else gets 403. Only RemoteAddr is used (no forwarding headers).
func PeerGate(e Endpoint, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !peerAllowed(e, r.RemoteAddr) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func peerAllowed(e Endpoint, remoteAddr string) bool {
	ap, err := netip.ParseAddrPort(remoteAddr)
	if err != nil {
		return false
	}
	peer := ap.Addr().Unmap()
	got, ok := Classify(peer)
	if !ok || got != e.Kind {
		return false
	}
	if e.Kind == LAN {
		return e.Subnet.IsValid() && e.Subnet.Contains(peer)
	}
	return true
}
