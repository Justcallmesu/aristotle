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
	cmdStatus        = "status"
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

// pairAddr is one listener the phone can use, as the phone also gets it (POST /v1/pair, GET /v1/addresses).
type pairAddr struct {
	httpapi.Address
	DefaultRoute bool `json:"default_route"`
}

type revokeArgs struct {
	ID string `json:"id"`
}

func (s *server) controlHandlers() map[string]control.Handler {
	return map[string]control.Handler{
		cmdPairStart:     s.pairStart,
		cmdDevicesList:   func(json.RawMessage) (any, error) { return s.devices.List(), nil },
		cmdDevicesRevoke: s.revoke,
		cmdStatus: func(json.RawMessage) (any, error) {
			return statusResult{Version: s.version, Addresses: s.addresses()}, nil
		},
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
	for _, a := range s.addresses() {
		res.Addresses = append(res.Addresses, pairAddr{Address: a, DefaultRoute: a.Kind == string(listen.LAN) && a.Iface == def})
	}
	return res, nil
}

// addresses lists the bound listeners, all https://: default-route LAN first, other LAN next, Tailscale last.
func (s *server) addresses() []httpapi.Address {
	active := s.listen.Active()
	out := make([]httpapi.Address, 0, len(active))
	for _, e := range active {
		out = append(out, httpapi.Address{URL: e.URL(s.cfg.Port), Kind: string(e.Kind), Iface: e.Iface})
	}
	return out
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

type statusResult struct {
	Version   string            `json:"version"`
	Addresses []httpapi.Address `json:"addresses"`
}
