package relay

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strconv"
	"unicode/utf8"
)

// Field names stripped from Hermes->phone frames (spec Q34, docs/protocol.md §10).
const (
	fieldSystemPrompt   = "system_prompt"
	fieldSystemChars    = "system_prompt_chars"
	fieldCredentialHint = "credential_hint"
)

var (
	needleSystemPrompt   = []byte(`"` + fieldSystemPrompt + `"`)
	needleCredentialHint = []byte(`"` + fieldCredentialHint + `"`)
)

// objectPaths are the objects whose redacted fields are stripped (docs/protocol.md §10):
// event payloads, the session.info result, and the info of create/resume/activate results.
var objectPaths = [][]string{
	{"params", "payload"},
	{"result"},
	{"result", "info"},
}

// Redact applies docs/protocol.md §10 to one Hermes->phone frame: at the listed paths (and in
// every element of result.events[]) system_prompt becomes system_prompt_chars and credential_hint
// is removed. A frame without either field name is returned as is, byte for byte. A frame that has
// one of the names but is not a JSON object is an error: it cannot be checked, so it must not pass.
func Redact(frame []byte) ([]byte, error) {
	if !bytes.Contains(frame, needleSystemPrompt) && !bytes.Contains(frame, needleCredentialHint) {
		return frame, nil
	}
	var top map[string]json.RawMessage
	if err := json.Unmarshal(frame, &top); err != nil || top == nil {
		return nil, fmt.Errorf("redact: frame is not a JSON object")
	}
	changed := false
	for _, path := range objectPaths {
		c, err := atPath(top, path, redactFields)
		if err != nil {
			return nil, err
		}
		changed = changed || c
	}
	c, err := atPath(top, []string{"result"}, redactEvents)
	if err != nil {
		return nil, err
	}
	if !changed && !c {
		return frame, nil
	}
	return encode(top)
}

// atPath runs fn on the object at path below m and writes the object back if fn changed it. A missing
// key or a value that is not an object (null, array, string) means there is nothing to redact there.
func atPath(m map[string]json.RawMessage, path []string, fn func(map[string]json.RawMessage) (bool, error)) (bool, error) {
	if len(path) == 0 {
		return fn(m)
	}
	child, ok := object(m[path[0]])
	if !ok {
		return false, nil
	}
	changed, err := atPath(child, path[1:], fn)
	if err != nil || !changed {
		return false, err
	}
	enc, err := encode(child)
	if err != nil {
		return false, err
	}
	m[path[0]] = enc
	return true, nil
}

func object(raw json.RawMessage) (map[string]json.RawMessage, bool) {
	if len(raw) == 0 {
		return nil, false
	}
	var m map[string]json.RawMessage
	if json.Unmarshal(raw, &m) != nil || m == nil {
		return nil, false
	}
	return m, true
}

// redactFields replaces system_prompt with its length in characters and drops credential_hint.
func redactFields(obj map[string]json.RawMessage) (bool, error) {
	changed := false
	if raw, ok := obj[fieldSystemPrompt]; ok {
		obj[fieldSystemChars] = json.RawMessage(strconv.Itoa(chars(raw)))
		delete(obj, fieldSystemPrompt)
		changed = true
	}
	if _, ok := obj[fieldCredentialHint]; ok {
		delete(obj, fieldCredentialHint)
		changed = true
	}
	return changed, nil
}

// chars is the length of a JSON string in characters (code points); null or a non-string is 0.
func chars(raw json.RawMessage) int {
	var s string
	if json.Unmarshal(raw, &s) != nil {
		return 0
	}
	return utf8.RuneCountInString(s)
}

// redactEvents applies the event rule to every element of result.events (session.events.since).
func redactEvents(result map[string]json.RawMessage) (bool, error) {
	raw, ok := result["events"]
	if !ok {
		return false, nil
	}
	var events []json.RawMessage
	if json.Unmarshal(raw, &events) != nil {
		return false, nil
	}
	changed := false
	for i, ev := range events {
		obj, ok := object(ev)
		if !ok {
			continue
		}
		c, err := atPath(obj, []string{"params", "payload"}, redactFields)
		if err != nil {
			return false, err
		}
		if !c {
			continue
		}
		if events[i], err = encode(obj); err != nil {
			return false, err
		}
		changed = true
	}
	if !changed {
		return false, nil
	}
	enc, err := encode(events)
	if err != nil {
		return false, err
	}
	result["events"] = enc
	return true, nil
}

// encode marshals without HTML escaping, so text fields keep their bytes (<, >, & stay as they are).
func encode(v any) ([]byte, error) {
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return nil, err
	}
	return bytes.TrimSuffix(buf.Bytes(), []byte("\n")), nil
}
