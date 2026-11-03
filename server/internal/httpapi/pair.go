package httpapi

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/netip"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

const (
	maxPairBody   = 4 << 10
	maxNameRunes  = 64
	defaultDevice = "device"
)

// pairReadTimeout bounds reading the /v1/pair body. It is set per request
// (not as a server-wide ReadTimeout) so long-lived WebSockets stay open.
var pairReadTimeout = 10 * time.Second

type pairRequest struct {
	Code       string `json:"code"`
	DeviceName string `json:"device_name"`
}

type pairResponse struct {
	DeviceID string `json:"device_id"`
	Token    string `json:"token"`
}

// pairHandler redeems the one-time code from the body (never the URL) and
// returns a new device token. The name given on the laptop (pair --name)
// wins over the phone's device_name.
func pairHandler(d Deps, log *slog.Logger, rejects *RejectLog) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Unsupported only on writers without a connection (tests); ignore.
		_ = http.NewResponseController(w).SetReadDeadline(time.Now().Add(pairReadTimeout))
		req, err := decodePair(w, r)
		if err != nil {
			writeError(w, http.StatusBadRequest, err.Error())
			return
		}
		laptopName, err := d.Codes.Redeem(req.Code, peerIP(r))
		if err != nil {
			if errors.Is(err, pairing.ErrExhausted) {
				log.Warn("pairing code invalidated: too many wrong attempts", "limit", pairing.MaxAttemptsTotal)
			}
			rejects.note()
			writeError(w, http.StatusUnauthorized, pairing.ErrInvalid.Error())
			return
		}
		dev, token, err := d.Devices.Add(pickName(laptopName, req.DeviceName))
		if err != nil {
			log.Error("pairing: store device", "err", err)
			writeError(w, http.StatusInternalServerError, "could not store the device; run pair again")
			return
		}
		log.Info("device paired", "device_id", dev.ID, "name", dev.Name)
		writeJSON(w, http.StatusCreated, pairResponse{DeviceID: dev.ID, Token: token})
	})
}

// peerIP is the client's address from RemoteAddr (never a forwarding
// header), or the zero Addr when it cannot be parsed.
func peerIP(r *http.Request) netip.Addr {
	ap, err := netip.ParseAddrPort(r.RemoteAddr)
	if err != nil {
		return netip.Addr{}
	}
	return ap.Addr().Unmap()
}

func decodePair(w http.ResponseWriter, r *http.Request) (pairRequest, error) {
	var req pairRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxPairBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		return req, errors.New("body must be JSON {\"code\", \"device_name\"}")
	}
	if strings.TrimSpace(req.Code) == "" {
		return req, errors.New("code is required")
	}
	name, err := cleanName(req.DeviceName)
	if err != nil {
		return req, errors.New("device_name " + err.Error())
	}
	req.DeviceName = name
	return req, nil
}

// cleanName strips control characters (Cc), format characters (Cf: bidi
// overrides and isolates, zero-width joiners), line/paragraph separators and
// invalid UTF-8, trims spaces and caps the result at 64 characters (longer
// is an error).
func cleanName(name string) (string, error) {
	name = strings.TrimSpace(strings.Map(func(r rune) rune {
		if unsafeNameRune(r) {
			return -1
		}
		return r
	}, name))
	if utf8.RuneCountInString(name) > maxNameRunes {
		return "", errors.New("is longer than 64 characters")
	}
	return name, nil
}

func unsafeNameRune(r rune) bool {
	return r == utf8.RuneError || unicode.In(r, unicode.Cc, unicode.Cf, unicode.Zl, unicode.Zp)
}

func pickName(laptop, phone string) string {
	switch {
	case laptop != "":
		return laptop
	case phone != "":
		return phone
	}
	return defaultDevice
}

// CleanName cleans a device name given on the laptop (pair --name) with the
// same rules as the phone's device_name.
func CleanName(name string) (string, error) { return cleanName(name) }
