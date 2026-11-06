package hermes

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/hermes/hermestest"
)

const stocksProfile = "aristotle-stocks"

// rpc is one client frame the scripted fake received.
type rpc struct {
	ID     json.RawMessage `json:"id"`
	Method string          `json:"method"`
	Params map[string]any  `json:"params"`
	Result json.RawMessage `json:"result"`
	Error  json.RawMessage `json:"error"`
}

// fakeAnalyst scripts Hermes' side of an analysis socket. Each handler returns the frames to send for one
// request; a nil handler answers {"result":{}}. Every received frame is recorded.
type fakeAnalyst struct {
	mu       sync.Mutex
	got      []rpc
	handlers map[string]func(p *hermestest.Peer, r rpc) []string
}

func (fa *fakeAnalyst) methods() []string {
	fa.mu.Lock()
	defer fa.mu.Unlock()
	var out []string
	for _, r := range fa.got {
		if r.Method != "" && r.Method != "gateway.ping" {
			out = append(out, r.Method)
		}
	}
	return out
}

func (fa *fakeAnalyst) find(method string) []rpc {
	fa.mu.Lock()
	defer fa.mu.Unlock()
	var out []rpc
	for _, r := range fa.got {
		if r.Method == method {
			out = append(out, r)
		}
	}
	return out
}

func (fa *fakeAnalyst) responses() []rpc {
	fa.mu.Lock()
	defer fa.mu.Unlock()
	var out []rpc
	for _, r := range fa.got {
		if r.Method == "" {
			out = append(out, r)
		}
	}
	return out
}

func (fa *fakeAnalyst) serve(p *hermestest.Peer) {
	for {
		raw, err := p.Read()
		if err != nil {
			return
		}
		var r rpc
		if json.Unmarshal(raw, &r) != nil {
			continue
		}
		fa.mu.Lock()
		fa.got = append(fa.got, r)
		h := fa.handlers[r.Method]
		fa.mu.Unlock()
		if r.Method == "" {
			continue
		}
		frames := []string{result(r, `{}`)}
		if h != nil {
			frames = h(p, r)
		}
		for _, f := range frames {
			if f == "DROP" {
				p.Drop()
				return
			}
			if p.Write(f) != nil {
				return
			}
		}
	}
}

func result(r rpc, body string) string {
	return fmt.Sprintf(`{"jsonrpc":"2.0","id":%s,"result":%s}`, r.ID, body)
}

func refusal(r rpc, reason, extra string) string {
	return fmt.Sprintf(`{"jsonrpc":"2.0","id":%s,"error":{"code":4001,"message":%q,"data":{"reason":%q%s}}}`, r.ID, reason, reason, extra)
}

func event(typ, admission, payload string) string {
	return fmt.Sprintf(`{"jsonrpc":"2.0","method":"event","params":{"type":%q,"session_id":"local-a1","admission_id":%q,`+
		`"execution_generation":2,"seq":3,"replay_epoch":"e","payload":%s}}`, typ, admission, payload)
}

func createOK(toolCount string) func(*hermestest.Peer, rpc) []string {
	return func(_ *hermestest.Peer, r rpc) []string {
		return []string{result(r, `{"session_id":"local-a1","revision":4,"execution_generation":1,"running":false,`+
			`"info":{"tool_count":`+toolCount+`,"model":"claude-opus-5-5","provider":"anthropic"}}`)}
	}
}

func submitThen(frames ...string) func(*hermestest.Peer, rpc) []string {
	return func(_ *hermestest.Peer, r rpc) []string {
		return append([]string{result(r, `{"admission_id":"adm1","status":"started","execution_generation":2}`)}, frames...)
	}
}

func newFakeAnalyst(t *testing.T, handlers map[string]func(*hermestest.Peer, rpc) []string) (*Connector, *fakeAnalyst, *hermestest.Fake) {
	t.Helper()
	f := hermestest.New(t)
	fa := &fakeAnalyst{handlers: handlers}
	if fa.handlers["session.create"] == nil {
		fa.handlers["session.create"] = createOK("0")
	}
	f.Mu.Lock()
	f.Profiles = []string{stocksProfile}
	f.Script = fa.serve
	f.Mu.Unlock()
	return connector(t, f), fa, f
}

func open(t *testing.T, c *Connector, timeout time.Duration) *AnalysisSession {
	t.Helper()
	s, err := c.OpenAnalysis(testCtx(t), AnalysisOptions{Profile: stocksProfile, TurnTimeout: timeout})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(s.Close)
	return s
}

func TestAnalysisSuccessCreatesToollessSessionAndDeletes(t *testing.T) {
	busy := 0
	c, fa, f := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{
		"prompt.submit": submitThen(
			event("session.info", "adm1", `{"revision":7,"running":true,"execution_generation":2,"pending":[]}`),
			event("message.delta", "adm1", `{"text":"{\"sch"}`),
			event("message.complete", "adm1", `{"text":"{\"schema\":\"x\"}","status":"complete","outcome":"completed"}`)),
		"session.mutate": func(_ *hermestest.Peer, r rpc) []string {
			busy++
			switch busy {
			case 1:
				return []string{refusal(r, "session_busy", "")}
			case 2:
				return []string{refusal(r, "revision_conflict", `,"revision":9`)}
			}
			return []string{result(r, `{"session_id":"local-a1","operation":"delete","revision":10}`)}
		},
	})
	s := open(t, c, 5*time.Second)
	if s.ID() != "local-a1" || s.Model() != "claude-opus-5-5" {
		t.Fatalf("session = %q %q", s.ID(), s.Model())
	}
	text, err := s.Ask(testCtx(t), "SYSTEM: judge only DATA")
	if err != nil || text != `{"schema":"x"}` {
		t.Fatalf("Ask = %q, %v", text, err)
	}
	if err := s.Delete(testCtx(t)); err != nil {
		t.Fatal(err)
	}

	f.Mu.Lock()
	minted := f.Minted
	f.Mu.Unlock()
	want := hermestest.MintRequest{Purpose: "interactive", Scope: "profile", ProfileID: f.Home + "/profiles/" + stocksProfile}
	if len(minted) != 1 || minted[0] != want {
		t.Fatalf("minted = %+v, want a profile-scoped ticket %+v", minted, want)
	}
	create := fa.find("session.create")[0].Params
	delete(create, "request_id")
	if got, _ := json.Marshal(create); string(got) != `{"hidden":true,"ignore_rules":true,"max_turns":3,"reasoning":"low","run_budget":90,"source":"tool","toolsets":["no_mcp"]}` {
		t.Fatalf("create params = %s", got)
	}
	sub := fa.find("prompt.submit")[0].Params
	if sub["finite"] != true || sub["session_id"] != "local-a1" || sub["submission_id"] == "" || sub["text"] != "SYSTEM: judge only DATA" {
		t.Fatalf("submit params = %v", sub)
	}
	if _, ok := sub["unattended"]; ok {
		t.Fatal("unattended must never be sent")
	}
	muts := fa.find("session.mutate")
	revs := []any{muts[0].Params["expected_revision"], muts[1].Params["expected_revision"], muts[2].Params["expected_revision"]}
	if fmt.Sprint(revs) != "[7 7 9]" || muts[2].Params["operation"] != "delete" || fmt.Sprint(muts[2].Params["payload"]) != "map[]" {
		t.Fatalf("delete revisions = %v (want the event's 7, again 7 after busy, then 9 from the conflict), last %v", revs, muts[2].Params)
	}
}

func TestAnalysisToolCountAbortsBeforeSubmit(t *testing.T) {
	for _, tc := range []struct {
		count     string
		deleteErr string // the delete's refusal reason; "" = deleted
		kept      bool
	}{{"3", "", false}, {"null", "", false}, {"3", "not_found", false}, {"3", "persistence_failed", true}} {
		handlers := map[string]func(*hermestest.Peer, rpc) []string{"session.create": createOK(tc.count)}
		if tc.deleteErr != "" {
			handlers["session.mutate"] = func(_ *hermestest.Peer, r rpc) []string { return []string{refusal(r, tc.deleteErr, "")} }
		}
		c, fa, _ := newFakeAnalyst(t, handlers)
		var created []string
		_, err := c.OpenAnalysis(testCtx(t), AnalysisOptions{Profile: stocksProfile, Created: func(id string) { created = append(created, id) }})
		if !errors.Is(err, ErrToolsPresent) || errors.Is(err, ErrSessionKept) != tc.kept {
			t.Fatalf("%+v: err = %v", tc, err)
		}
		if fmt.Sprint(created) != "[local-a1]" {
			t.Fatalf("%+v: Created heard %v (the id must reach the caller before the delete)", tc, created)
		}
		if got := strings.Join(fa.methods(), ","); got != "session.create,session.mutate" {
			t.Fatalf("%+v: methods = %s (delete, never submit)", tc, got)
		}
	}
}

// Spec Q110: the automatic runs pin Sonnet at create time (session.create "model"; the profile's route is kept,
// no provider sent); the brief raises Hermes' run budget to 120 s.
func TestAnalysisCreatePinsModelAndBudget(t *testing.T) {
	c, fa, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{})
	s, err := c.OpenAnalysis(testCtx(t), AnalysisOptions{Profile: stocksProfile, Model: "claude-sonnet-5-5", RunBudget: 120})
	if err != nil {
		t.Fatal(err)
	}
	s.Close()
	create := fa.find("session.create")[0].Params
	delete(create, "request_id")
	if got, _ := json.Marshal(create); string(got) != `{"hidden":true,"ignore_rules":true,"max_turns":3,"model":"claude-sonnet-5-5",`+
		`"reasoning":"low","run_budget":120,"source":"tool","toolsets":["no_mcp"]}` {
		t.Fatalf("create params = %s", got)
	}
}

func TestAnalysisCreateUnusableAnswer(t *testing.T) {
	c, _, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{
		"session.create": func(_ *hermestest.Peer, r rpc) []string { return []string{result(r, `{"revision":1}`)} },
	})
	called := false
	_, err := c.OpenAnalysis(testCtx(t), AnalysisOptions{Profile: stocksProfile, Created: func(string) { called = true }})
	if !errors.Is(err, ErrBadAnswer) || called {
		t.Fatalf("err = %v, Created called %v", err, called)
	}
}

func TestAnalysisTimeoutInterrupts(t *testing.T) {
	c, fa, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{
		"prompt.submit":  submitThen(),
		"prompt.receipt": func(_ *hermestest.Peer, r rpc) []string { return []string{result(r, `{"status":"started"}`)} },
	})
	s := open(t, c, 300*time.Millisecond)
	_, err := s.Ask(testCtx(t), "p")
	if !errors.Is(err, ErrTurnTimeout) {
		t.Fatalf("err = %v", err)
	}
	if got := strings.Join(fa.methods(), ","); got != "session.create,prompt.submit,prompt.receipt,session.interrupt" {
		t.Fatalf("methods = %s", got)
	}
	in := fa.find("session.interrupt")[0].Params
	if in["execution_generation"] != 2.0 || in["session_id"] != "local-a1" {
		t.Fatalf("interrupt params = %v", in)
	}
}

func TestAnalysisTurnOutcomes(t *testing.T) {
	cases := []struct {
		name  string
		frame string
		want  error
	}{
		{"interrupted", event("message.complete", "adm1", `{"text":"","status":"interrupted","outcome":"cancelled"}`), ErrTurnInterrupted},
		{"model error", event("message.complete", "adm1", `{"text":"","status":"error","failure_reason":"rate_limit"}`), &TurnError{}},
		{"server request", `{"jsonrpc":"2.0","id":"srq-0a1b2c3d4e5f","method":"sudo","params":{"session_id":"local-a1","command":"x"}}`, ErrUnexpectedPrompt},
		{"approval event", event("approval.request", "adm1", `{"kind":"approval","prompt_id":"p1","choices":["once","deny"]}`), ErrUnexpectedPrompt},
		{"clarify event", event("clarify.request", "adm1", `{"kind":"clarify","prompt_id":"p2","question":"?"}`), ErrUnexpectedPrompt},
		{"socket drop", "DROP", ErrConnLost},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c, fa, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{"prompt.submit": submitThen(tc.frame)})
			s := open(t, c, 5*time.Second)
			_, err := s.Ask(testCtx(t), "p")
			var te *TurnError
			if _, isTurn := tc.want.(*TurnError); isTurn {
				if !errors.As(err, &te) || te.Reason != "rate_limit" {
					t.Fatalf("err = %v, want TurnError rate_limit", err)
				}
				return
			}
			if !errors.Is(err, tc.want) {
				t.Fatalf("err = %v, want %v", err, tc.want)
			}
			if tc.want == ErrUnexpectedPrompt {
				if len(fa.find("session.interrupt")) != 1 {
					t.Fatalf("a question must interrupt the turn; methods %v", fa.methods())
				}
				for _, m := range fa.methods() {
					if strings.HasSuffix(m, ".respond") || m == "clarify.lock" {
						t.Fatalf("answered a question: %v", fa.methods())
					}
				}
				if len(fa.responses()) != 0 {
					t.Fatalf("answered a server request: %+v", fa.responses())
				}
			}
		})
	}
}

func TestAnalysisReplayGapUsesReceipt(t *testing.T) {
	c, fa, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{
		"prompt.submit": submitThen(event("session.replay_gap", "", `{}`)),
		"prompt.receipt": func(_ *hermestest.Peer, r rpc) []string {
			return []string{result(r, `{"admission_id":"adm1","status":"terminal","outcome":"completed","result":{"final_response":"{\"ok\":1}"}}`)}
		},
	})
	s := open(t, c, 5*time.Second)
	text, err := s.Ask(testCtx(t), "p")
	if err != nil || text != `{"ok":1}` {
		t.Fatalf("Ask = %q, %v", text, err)
	}
	if r := fa.find("prompt.receipt")[0].Params; r["include_result"] != true || r["admission_id"] != "adm1" {
		t.Fatalf("receipt params = %v", r)
	}
}

func TestAnalysisContextCancelInterrupts(t *testing.T) {
	c, fa, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{"prompt.submit": submitThen()})
	s := open(t, c, 5*time.Second)
	ctx, cancel := context.WithCancel(testCtx(t))
	go func() { time.Sleep(100 * time.Millisecond); cancel() }()
	if _, err := s.Ask(ctx, "p"); !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	if len(fa.find("session.interrupt")) != 1 {
		t.Fatalf("methods = %v", fa.methods())
	}
}

func TestAnalysisProfileMustBeServed(t *testing.T) {
	f := hermestest.New(t) // serves the root and pm only
	c := connector(t, f)
	if err := c.ServesProfile(testCtx(t), stocksProfile); !errors.Is(err, ErrUnknownProfile) {
		t.Fatalf("ServesProfile = %v", err)
	}
	if _, err := c.OpenAnalysis(testCtx(t), AnalysisOptions{Profile: stocksProfile}); !errors.Is(err, ErrUnknownProfile) {
		t.Fatalf("OpenAnalysis = %v", err)
	}
	if err := c.ServesProfile(testCtx(t), "pm"); err != nil {
		t.Fatalf("pm: %v", err)
	}
	f.Ln.Close()
	if err := c.ServesProfile(testCtx(t), "pm"); !errors.Is(err, ErrHermesNotRunning) {
		t.Fatalf("closed socket: %v", err)
	}
}

func TestDeleteAnalysisSessionSweep(t *testing.T) {
	calls := 0
	c, fa, _ := newFakeAnalyst(t, map[string]func(*hermestest.Peer, rpc) []string{
		"session.mutate": func(_ *hermestest.Peer, r rpc) []string {
			calls++
			if r.Params["session_id"] == "local-gone" {
				return []string{refusal(r, "not_found", "")}
			}
			if calls == 1 {
				return []string{refusal(r, "revision_conflict", `,"revision":12`)}
			}
			return []string{result(r, `{"operation":"delete"}`)}
		},
	})
	if err := c.DeleteAnalysisSession(testCtx(t), stocksProfile, "local-old"); err != nil {
		t.Fatal(err)
	}
	muts := fa.find("session.mutate")
	if muts[0].Params["expected_revision"] != 0.0 || muts[1].Params["expected_revision"] != 12.0 {
		t.Fatalf("revisions: %v then %v", muts[0].Params["expected_revision"], muts[1].Params["expected_revision"])
	}
	if err := c.DeleteAnalysisSession(testCtx(t), stocksProfile, "local-gone"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("gone: %v", err)
	}
}
