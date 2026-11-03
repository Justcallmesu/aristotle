package app

import (
	"encoding/json"
	"errors"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/listen"
)

// Control commands shared by the server handlers and the CLI.
const (
	cmdPairStart     = "pair.start"
	cmdDevicesList   = "devices.list"
	cmdDevicesRevoke = "devices.revoke"
)

type pairStartArgs struct {
	Name string `json:"name"`
}

type pairStartResult struct {
	Code        string     `json:"code"`
	ExpiresAt   time.Time  `json:"expires_at"`
	Fingerprint string     `json:"fingerprint"`
	Addresses   []pairAddr `json:"addresses"` // default-route LAN first, Tailscale last
}

// pairAddr is one listener the phone can use.
type pairAddr struct {
	URL          string `json:"url"`
	Kind         string `json:"kind"` // "lan" (HTTPS) or "tailscale" (HTTP)
	Iface        string `json:"iface"`
	DefaultRoute bool   `json:"default_route"`
}

type revokeArgs struct {
	ID string `json:"id"`
}

func (s *server) controlHandlers() map[string]control.Handler {
	return map[string]control.Handler{
		cmdPairStart:     s.pairStart,
		cmdDevicesList:   func(json.RawMessage) (any, error) { return s.devices.List(), nil },
		cmdDevicesRevoke: s.revoke,
	}
}

func (s *server) pairStart(raw json.RawMessage) (any, error) {
	var args pairStartArgs
	if err := decodeArgs(raw, &args); err != nil {
		return nil, err
	}
	name, err := httpapi.CleanName(args.Name)
	if err != nil {
		return nil, errors.New("--name " + err.Error())
	}
	iss, err := s.codes.Start(name)
	if err != nil {
		return nil, err
	}
	s.log.Info("pairing code issued", "expires_at", iss.ExpiresAt.Format(time.RFC3339))
	res := pairStartResult{Code: iss.Code, ExpiresAt: iss.ExpiresAt, Fingerprint: s.cert.Fingerprint}
	def := s.listen.DefaultRouteIface()
	for _, e := range s.listen.Active() {
		res.Addresses = append(res.Addresses, pairAddr{
			URL: e.URL(s.cfg.Port), Kind: string(e.Kind), Iface: e.Iface,
			DefaultRoute: e.Kind == listen.LAN && e.Iface == def,
		})
	}
	return res, nil
}

func (s *server) revoke(raw json.RawMessage) (any, error) {
	var args revokeArgs
	if err := decodeArgs(raw, &args); err != nil {
		return nil, err
	}
	dev, err := s.devices.Revoke(args.ID)
	if errors.Is(err, devices.ErrNotFound) {
		return nil, errors.New("no device with id " + args.ID)
	}
	if err != nil {
		return nil, err
	}
	s.log.Info("device revoked", "device_id", dev.ID, "name", dev.Name)
	return dev, nil
}

func decodeArgs(raw json.RawMessage, v any) error {
	if len(raw) == 0 {
		return nil
	}
	if err := json.Unmarshal(raw, v); err != nil {
		return errors.New("bad arguments")
	}
	return nil
}
