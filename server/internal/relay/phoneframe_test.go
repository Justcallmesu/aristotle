package relay

import (
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestInspectPhoneRefusesDesktopLocal(t *testing.T) {
	for _, tc := range []struct {
		frame  string
		refuse bool
		method string
	}{
		{`{"jsonrpc":"2.0","id":2,"method":"client.capabilities","params":{"desktop_local":true}}`, true, "client.capabilities"},
		{`{"method":"client.capabilities","params":{"desktop\u005flocal":true}}`, true, "client.capabilities"},
		{`{"method":"client.capabilities","params":{"desktop_local":false}}`, true, "client.capabilities"},
		{`{"method":"client.capabilities","params":{"desktop_local":null}}`, true, "client.capabilities"},
		{`{"method":"client.c\u0061pabilities","params":{"desktop_local":true}}`, true, "client.capabilities"},
		// encoding/json would match "Method" case-insensitively; Hermes reads only "method".
		{`{"method":"client.capabilities","Method":"session.list","params":{"desktop_local":true}}`, true, "client.capabilities"},
		// Hermes' parser accepts NaN; Go's cannot check such a frame, so it is refused when it may be capabilities.
		{`{"method":"client.capabilities","params":{"desktop_local":true,"x":NaN}}`, true, ""},
		{`{"method":"client.capabilitie\u0073","params":{"desktop_local":true},"x":NaN}`, true, ""},
		{`{"jsonrpc":"2.0","id":3,"method":"client.capabilities","params":{"server_requests":false}}`, false, "client.capabilities"},
		{`{"method":"client.capabilities","params":{"x":{"desktop_local":true}}}`, false, "client.capabilities"},
		{`{"method":"prompt.submit","params":{"text":"desktop_local capabilities"}}`, false, "prompt.submit"},
		{`{"id":"srq-1","result":{"desktop_local":true}}`, false, ""},
		{`not json`, false, ""},
	} {
		got := inspectPhone([]byte(tc.frame))
		if got.refuse != tc.refuse || got.method != tc.method {
			t.Errorf("%s: refuse=%v method=%q, want %v %q", tc.frame, got.refuse, got.method, tc.refuse, tc.method)
		}
	}
}

// Review T06 #3: only known Hermes method names reach the audit line.
func TestInspectPhoneCountsUnknownMethodsAsOther(t *testing.T) {
	for frame, want := range map[string]string{
		`{"method":"session.list"}`:                                        "session.list",
		`{"method":"Session.List"}`:                                        "other",
		`{"method":"kQ3v9xV0aT8m2LwZr5YbN1cH7pGfJ4sEeU6dRoMiAk0"}`:         "other",
		`{"method":"sk-proj-0123456789abcdefABCDEF"}`:                      "other",
		`{"method":"session.list.kQ3v9xV0aT8m2LwZr5YbN1cH7pGfJ4sEeU6dRo"}`: "other",
		`{"method":7}`:  "",
		`{"params":{}}`: "",
	} {
		if got := inspectPhone([]byte(frame)).method; got != want {
			t.Errorf("%s -> %q, want %q", frame, got, want)
		}
	}
}

// Q43: the phone may not claim to be the local Desktop app; the frame never reaches Hermes.
func TestRelayRefusesDesktopLocal(t *testing.T) {
	for _, frame := range []string{
		`{"jsonrpc":"2.0","id":2,"method":"client.capabilities","params":{"desktop_local":true}}`,
		`{"jsonrpc":"2.0","id":2,"method":"client.capabilities","params":{"desktop\u005flocal":true}}`,
		`{"jsonrpc":"2.0","id":2,"method":"client.capabilities","params":{"desktop_local":false}}`,
	} {
		h := newHarness(t, nil)
		pc := h.phone(t, h.token)
		write(t, pc, frame)
		if ce := wantClose(t, pc, CodeRefused, 5*time.Second); ce.Reason != "desktop_local_refused" {
			t.Fatalf("reason %q", ce.Reason)
		}
		time.Sleep(50 * time.Millisecond)
		h.fake.Mu.Lock()
		seen := strings.Join(h.fake.FirstFrames, "\n")
		h.fake.Mu.Unlock()
		if strings.Contains(seen, "desktop") {
			t.Fatalf("Hermes got the refused frame %s", frame)
		}
	}

	h := newHarness(t, nil)
	pc := h.phone(t, h.token)
	ok := `{"jsonrpc":"2.0","id":2,"method":"client.capabilities","params":{"server_requests":false}}`
	write(t, pc, ok)
	if got := read(t, pc); got != ok {
		t.Fatalf("normal client.capabilities: echo %s", got)
	}
	pc.Close(websocket.StatusNormalClosure, "")
}
