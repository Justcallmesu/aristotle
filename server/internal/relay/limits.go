package relay

import (
	"errors"
	"fmt"
	"maps"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/coder/websocket"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
)

// Close codes the relay sends to the phone (docs/protocol.md §12).
const (
	CodeGoingAway           websocket.StatusCode = 1001 // server shutting down
	CodeTextOnly            websocket.StatusCode = 1003 // the phone sent a binary frame
	CodeRefused             websocket.StatusCode = 1008 // the phone sent client.capabilities naming desktop_local
	CodeTooBig              websocket.StatusCode = 1009 // a phone frame over MaxFrameBytes (sent by the library)
	CodePongTimeout         websocket.StatusCode = 1011 // no pong within PongTimeout
	CodeTryAgain            websocket.StatusCode = 1013 // Hermes is draining or not ready (its own 1013)
	CodeRevoked             websocket.StatusCode = 4401 // the device was revoked
	CodeSuperseded          websocket.StatusCode = 4409 // the device opened a newer relay on the other listener
	CodeRateLimited         websocket.StatusCode = 4429 // more than MaxFramesPerMinute phone frames
	CodeTooMany             websocket.StatusCode = 4430 // the device already has MaxPerDevice relays
	CodeHermesUnavailable   websocket.StatusCode = 4502 // any other Hermes failure, or Hermes refused (4403)
	CodeHermesNotRunning    websocket.StatusCode = 4503 // Hermes control socket missing or refusing
	CodeHermesUnsupported   websocket.StatusCode = 4505 // Hermes speaks a runtime_protocol this build does not
	codeAbnormal            websocket.StatusCode = 1006 // never sent: the peer vanished without a close frame
	maxCloseReasonBytes                          = 123
	reasonHermesLost                             = "hermes_unavailable: connection lost"
	reasonHermesRefused                          = "hermes_unavailable: refused (4403)"
	reasonHermesUnavailable                      = "hermes_unavailable"
)

// closeReason is a close code plus the reason text sent with it.
type closeReason struct {
	code   websocket.StatusCode
	reason string
}

// dialClose maps a hermes.Connector.Dial error to the phone's close code.
func dialClose(err error) closeReason {
	switch {
	case errors.Is(err, hermes.ErrHermesNotRunning):
		return closeReason{CodeHermesNotRunning, "hermes_not_running"}
	case errors.Is(err, hermes.ErrUnsupportedProtocol):
		return closeReason{CodeHermesUnsupported, clip(fmt.Sprintf("hermes_protocol_unsupported (%v)", err))}
	case errors.Is(err, hermes.ErrDraining):
		return closeReason{CodeTryAgain, "try_again"}
	}
	return closeReason{CodeHermesUnavailable, reasonHermesUnavailable}
}

// hermesClose maps the end of an established Hermes socket to the phone's close code: Hermes' own code
// and reason pass through, except 4403 (refused) and codes that cannot be sent on the wire.
func hermesClose(err error) closeReason {
	var ce websocket.CloseError
	if !errors.As(err, &ce) {
		return closeReason{CodeHermesUnavailable, reasonHermesLost}
	}
	switch {
	case ce.Code == 4403:
		return closeReason{CodeHermesUnavailable, reasonHermesRefused}
	case !sendable(ce.Code):
		return closeReason{CodeHermesUnavailable, reasonHermesLost}
	}
	return closeReason{ce.Code, clip(ce.Reason)}
}

// phoneClose records how the phone side ended; the code is for the log (the phone already knows).
func phoneClose(err error) closeReason {
	var ce websocket.CloseError
	switch {
	case errors.As(err, &ce):
		return closeReason{ce.Code, ""}
	case errors.Is(err, websocket.ErrMessageTooBig):
		return closeReason{CodeTooBig, "message_too_big"}
	}
	return closeReason{codeAbnormal, ""}
}

// sendable reports whether code may appear in a close frame (RFC 6455 §7.4).
func sendable(code websocket.StatusCode) bool {
	switch code {
	case 1004, 1005, 1006, 1015:
		return false
	}
	return (code >= 1000 && code <= 1014) || (code >= 3000 && code <= 4999)
}

// clip shortens a close reason to the wire limit without splitting a character.
func clip(s string) string {
	if len(s) <= maxCloseReasonBytes {
		return s
	}
	s = s[:maxCloseReasonBytes]
	for !utf8.ValidString(s) {
		s = s[:len(s)-1]
	}
	return s
}

// window allows at most cap(times) events per period: the n-th event must be at least period after
// the event n-cap before it.
type window struct {
	period time.Duration
	times  []time.Time
	next   int
}

func newWindow(n int, period time.Duration) *window {
	return &window{period: period, times: make([]time.Time, 0, n)}
}

func (w *window) allow(now time.Time) bool {
	if len(w.times) < cap(w.times) {
		w.times = append(w.times, now)
		return true
	}
	if now.Sub(w.times[w.next]) < w.period {
		return false
	}
	w.times[w.next] = now
	w.next = (w.next + 1) % len(w.times)
	return true
}

// maxMethodNames bounds the per-relay method map; further names count as "other".
const maxMethodNames = 64

// traffic counts one direction. methods counts JSON-RPC method names (phone->Hermes only).
type traffic struct {
	frames, bytes int64
	methods       map[string]int
}

func (t *traffic) add(frame []byte) {
	t.frames++
	t.bytes += int64(len(frame))
}

// addMethod counts one method name; past maxMethodNames distinct names, new ones count as "other".
func (t *traffic) addMethod(name string) {
	if name == "" {
		return
	}
	if t.methods == nil {
		t.methods = map[string]int{}
	}
	if _, seen := t.methods[name]; !seen && len(t.methods) >= maxMethodNames {
		name = "other"
	}
	t.methods[name]++
}

// methodSummary formats the method counts as "a=1,b=2", sorted by name.
func methodSummary(m map[string]int) string {
	var b strings.Builder
	for i, name := range slices.Sorted(maps.Keys(m)) {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, "%s=%d", name, m[name])
	}
	return b.String()
}
