package app

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/hermes"
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
	handlers := map[string]control.Handler{
		cmdPairStart:     s.pairStart,
		cmdDevicesList:   func(json.RawMessage) (any, error) { return s.devices.List(), nil },
		cmdDevicesRevoke: s.revoke,
		cmdStatus: func(json.RawMessage) (any, error) {
			return statusResult{Version: s.version, Addresses: s.addresses()}, nil
		},
	}
	for cmd, h := range s.terminalHandlers() {
		handlers[cmd] = h
	}
	return handlers
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
	// Its terminals are already closed (revoke hook, 4401); the grant would only be a stale entry.
	if _, err := s.grants.Ungrant(dev.ID); err != nil {
		s.log.Warn("could not remove the revoked device's terminal grant", "device_id", dev.ID, "err", err)
	}
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

// tailscaleUp: a Tailscale listener is bound right now (§16).
func (s *server) tailscaleUp() bool {
	for _, e := range s.listen.Active() {
		if e.Kind == listen.Tailscale {
			return true
		}
	}
	return false
}

// hermesRunning is the check behind relay close 4503: the control socket is missing or refuses. Any other
// answer (even an unsupported protocol) means Hermes is up; a timeout leaves the field null.
func hermesRunning(c *hermes.Connector) func(context.Context) (bool, error) {
	return func(ctx context.Context) (bool, error) {
		ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
		defer cancel()
		_, err := c.Identify(ctx)
		switch {
		case err == nil:
			return true, nil
		case errors.Is(err, hermes.ErrHermesNotRunning):
			return false, nil
		case ctx.Err() != nil:
			return false, ctx.Err()
		}
		return true, nil
	}
}
