package relay

import (
	"bytes"
	"encoding/json"
)

// knownMethods are the JSON-RPC method names the audit line may carry (docs/protocol.md §1-§9). Any
// other name counts as "other", so a token or key sent as a method never reaches the log.
var knownMethods = map[string]bool{
	"approval.respond": true, "clarify.respond": true, "client.capabilities": true, "command.dispatch": true,
	"commands.catalog": true, "complete.slash": true, "config.get": true, "config.set": true,
	"cron.cancel": true, "cron.recover": true, "cron.status": true, "cron.submit": true,
	"file.attach": true, "gateway.ping": true, "image.attach_bytes": true, "model.options": true,
	"prompt.background": true, "prompt.cancel": true, "prompt.receipt": true, "prompt.replace_queued": true,
	"prompt.steer_queued": true, "prompt.submit": true, "runtime.describe": true, "session.create": true,
	"session.detach": true, "session.events.since": true, "session.info": true, "session.interrupt": true,
	"session.list": true, "session.mutate": true, "session.redirect": true, "session.resume": true,
	"session.steer": true, "shell.exec": true, "slash.exec": true, "subagent.interrupt": true,
	"subagent.list": true, "subagent.steer": true, "subagent.tail": true,
}

const (
	methodCapabilities = "client.capabilities"
	keyDesktopLocal    = "desktop_local"
)

// phoneFrame is what the relay reads from a phone frame before forwarding it.
type phoneFrame struct {
	method string // the audit name: a known method, "other", or "" when the frame has no string method
	refuse bool   // the frame claims desktop_local (or might, and cannot be checked); it must not reach Hermes
}

// inspectPhone decodes a phone frame the way Hermes does: exact key names (encoding/json's struct
// matching is case-insensitive, Python's is not) and escapes resolved, so `desktop\u005flocal` is caught.
func inspectPhone(frame []byte) phoneFrame {
	var top map[string]json.RawMessage
	if json.Unmarshal(frame, &top) != nil {
		// Hermes' parser also accepts what Go's does not (NaN, Infinity, leading Unicode spaces). Such a
		// frame is refused when it may spell client.capabilities: literally, or through a \u escape.
		return phoneFrame{refuse: bytes.Contains(frame, []byte("capabilities")) || bytes.Contains(frame, []byte(`\u`))}
	}
	var name string
	if raw, ok := top["method"]; !ok || json.Unmarshal(raw, &name) != nil {
		return phoneFrame{}
	}
	f := phoneFrame{method: "other"}
	if knownMethods[name] {
		f.method = name
	}
	f.refuse = name == methodCapabilities && namesDesktopLocal(top["params"])
	return f
}

// namesDesktopLocal reports whether params is an object with a desktop_local key, whatever its value:
// Hermes acts on the key's presence (tui_gateway/methods_voice.py:535).
func namesDesktopLocal(params json.RawMessage) bool {
	var p map[string]json.RawMessage
	if json.Unmarshal(params, &p) != nil {
		return false
	}
	_, ok := p[keyDesktopLocal]
	return ok
}
