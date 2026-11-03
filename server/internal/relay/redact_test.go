package relay

import (
	"bufio"
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"unicode/utf8"
)

const framesDir = "../../../docs/spikes/frames"

// capturedFrame returns the raw bytes of the "frame" field of line n (1-based) of a spike capture, as
// they appear in the file.
func capturedFrame(t *testing.T, file string, n int) []byte {
	t.Helper()
	lines := captureLines(t, file)
	if n < 1 || n > len(lines) {
		t.Fatalf("%s has %d lines, want line %d", file, len(lines), n)
	}
	var rec struct {
		Frame json.RawMessage `json:"frame"`
	}
	if err := json.Unmarshal(lines[n-1], &rec); err != nil || len(rec.Frame) == 0 {
		t.Fatalf("%s:%d: no frame (%v)", file, n, err)
	}
	return rec.Frame
}

func captureLines(t *testing.T, file string) [][]byte {
	t.Helper()
	f, err := os.Open(filepath.Join(framesDir, file))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	var lines [][]byte
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 1<<20), 8<<20)
	for sc.Scan() {
		lines = append(lines, bytes.Clone(sc.Bytes()))
	}
	if err := sc.Err(); err != nil {
		t.Fatal(err)
	}
	return lines
}

func decode(t *testing.T, b []byte) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("decode: %v", err)
	}
	return m
}

// at returns the object at path in m.
func at(t *testing.T, m map[string]any, path ...string) map[string]any {
	t.Helper()
	for _, k := range path {
		next, ok := m[k].(map[string]any)
		if !ok {
			t.Fatalf("no object at %v", path)
		}
		m = next
	}
	return m
}

// expectRedacted applies the §10 rule by hand to the object at path, for comparison.
func expectRedacted(t *testing.T, m map[string]any, path ...string) {
	t.Helper()
	obj := at(t, m, path...)
	if sp, ok := obj["system_prompt"]; ok {
		s, _ := sp.(string)
		obj["system_prompt_chars"] = float64(utf8.RuneCountInString(s))
		delete(obj, "system_prompt")
	}
	delete(obj, "credential_hint")
}

func redactOK(t *testing.T, frame []byte) []byte {
	t.Helper()
	out, err := Redact(frame)
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func TestRedactShapeAPassesByteForByte(t *testing.T) {
	for _, n := range []int{15, 17, 29} {
		frame := capturedFrame(t, "c-client1.jsonl", n)
		if !strings.Contains(string(frame), `"session.info"`) || !strings.Contains(string(frame), `"revision"`) {
			t.Fatalf("c-client1.jsonl:%d is not a shape-A session.info", n)
		}
		if out := redactOK(t, frame); !bytes.Equal(out, frame) {
			t.Fatalf("c-client1.jsonl:%d changed:\n%s", n, out)
		}
	}
}

func TestRedactShapeBEvent(t *testing.T) {
	frame := capturedFrame(t, "c-client1.jsonl", 26)
	want := decode(t, frame)
	prompt, _ := at(t, want, "params", "payload")["system_prompt"].(string)
	if len(prompt) < 20 { // the spike stored a placeholder of the prompt, not the 21 KB text
		t.Fatalf("capture has no system prompt value (%d bytes)", len(prompt))
	}
	expectRedacted(t, want, "params", "payload")
	out := redactOK(t, frame)
	if got := decode(t, out); !reflect.DeepEqual(got, want) {
		t.Fatalf("redacted shape B differs from the expected frame")
	}
	if bytes.Contains(out, []byte(prompt)) || bytes.Contains(out, []byte(`"credential_hint"`)) {
		t.Fatal("prompt text or credential_hint survived")
	}
}

func TestRedactResults(t *testing.T) {
	for _, tc := range []struct {
		file      string
		line      int
		path      []string
		hasPrompt bool
	}{
		{"c-send.jsonl", 8, []string{"result", "info"}, true},         // session.resume after a turn
		{"c-client1.jsonl", 7, []string{"result", "info"}, false},     // lazy resume: credential_hint null, no prompt
		{"c-create.jsonl", 5, []string{"result", "info"}, false},      // session.create
		{"f-busy-client2.jsonl", 85, []string{"result"}, true},        // session.info result
		{"f-busy-client2.jsonl", 7, []string{"result", "info"}, true}, // resume of a busy session
	} {
		frame := capturedFrame(t, tc.file, tc.line)
		want := decode(t, frame)
		_, had := at(t, want, tc.path...)["system_prompt"]
		if had != tc.hasPrompt {
			t.Fatalf("%s:%d: system_prompt present=%v, test expects %v", tc.file, tc.line, had, tc.hasPrompt)
		}
		expectRedacted(t, want, tc.path...)
		got := decode(t, redactOK(t, frame))
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("%s:%d: redacted frame differs from expected", tc.file, tc.line)
		}
		obj := at(t, got, tc.path...)
		if _, ok := obj["system_prompt_chars"]; ok != tc.hasPrompt {
			t.Fatalf("%s:%d: system_prompt_chars present=%v", tc.file, tc.line, ok)
		}
	}
}

// Not captured (protocol.md §10): a replayed shape-B event inside session.events.since.
func TestRedactEventsSinceElements(t *testing.T) {
	shapeB := capturedFrame(t, "c-client1.jsonl", 26)
	shapeA := capturedFrame(t, "c-client1.jsonl", 15)
	frame := []byte(`{"jsonrpc":"2.0","id":9,"result":{"events":[` + string(shapeB) + `,` + string(shapeA) +
		`,5,null],"latest_seq":3,"truncated":false}}`)
	want := decode(t, frame)
	events := at(t, want, "result")["events"].([]any)
	expectRedacted(t, events[0].(map[string]any), "params", "payload")
	got := decode(t, redactOK(t, frame))
	if !reflect.DeepEqual(got, want) {
		t.Fatal("events[] element not redacted as expected")
	}
}

func TestRedactSystemPromptChars(t *testing.T) {
	for in, want := range map[string]string{
		`"héllo 😀"`: `7`, // characters (code points), not bytes (11)
		`""`:        `0`,
		`null`:      `0`,
		`42`:        `0`,
	} {
		out := redactOK(t, []byte(`{"result":{"system_prompt":`+in+`,"x":1}}`))
		if wantOut := `{"result":{"system_prompt_chars":` + want + `,"x":1}}`; string(out) != wantOut {
			t.Errorf("system_prompt %s -> %s, want %s", in, out, wantOut)
		}
	}
}

// Field names elsewhere (tool args, message text) are not touched; the frame passes byte for byte.
func TestRedactOnlyListedPaths(t *testing.T) {
	for _, frame := range []string{
		`{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","payload":{"args":{"system_prompt":"x","credential_hint":"y"}}}}`,
		`{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"text":"the \"system_prompt\" key"}}}`,
		`{"jsonrpc":"2.0","id":3,"result":{"messages":[{"system_prompt":"kept"}],"info":null}}`,
		`{"jsonrpc":"2.0","id":3,"result":["system_prompt","credential_hint"]}`,
	} {
		if out := redactOK(t, []byte(frame)); string(out) != frame {
			t.Errorf("frame changed:\n in %s\nout %s", frame, out)
		}
	}
}

func TestRedactKeepsTextBytes(t *testing.T) {
	out := redactOK(t, []byte(`{"result":{"credential_hint":"sk-…","title":"<b>a & b</b>"}}`))
	if string(out) != `{"result":{"title":"<b>a & b</b>"}}` {
		t.Fatalf("got %s", out)
	}
}

// A frame that names a redacted field but cannot be parsed must not pass.
func TestRedactRefusesUnparsable(t *testing.T) {
	for _, frame := range []string{`{"system_prompt":`, `["system_prompt"]`, `"credential_hint"`} {
		if out, err := Redact([]byte(frame)); err == nil {
			t.Errorf("%s passed as %s", frame, out)
		}
	}
}

// Every frame Hermes sent in the spike: none keeps either field at any depth of a listed path, and
// every frame without the field names is unchanged byte for byte.
func TestRedactEveryCapturedFrame(t *testing.T) {
	files, err := filepath.Glob(filepath.Join(framesDir, "*.jsonl"))
	if err != nil || len(files) == 0 {
		t.Fatalf("no captures: %v", err)
	}
	redacted := 0
	for _, path := range files {
		for i, line := range captureLines(t, filepath.Base(path)) {
			var rec struct {
				Dir   string          `json:"dir"`
				Frame json.RawMessage `json:"frame"`
			}
			if json.Unmarshal(line, &rec) != nil || rec.Dir != "in" || len(rec.Frame) == 0 || rec.Frame[0] != '{' {
				continue
			}
			out := redactOK(t, rec.Frame)
			if !bytes.Equal(out, rec.Frame) {
				redacted++
			}
			if bytes.Contains(out, needleSystemPrompt) || bytes.Contains(out, needleCredentialHint) {
				t.Errorf("%s:%d still names a redacted field", filepath.Base(path), i+1)
			}
		}
	}
	if redacted < 20 {
		t.Fatalf("only %d frames redacted; the captures hold 16 shape-B events and 8 results", redacted)
	}
}
