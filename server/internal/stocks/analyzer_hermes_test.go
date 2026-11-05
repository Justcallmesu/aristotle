package stocks

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/justcallmesu/aristotle/server/internal/hermes"
	"github.com/justcallmesu/aristotle/server/internal/hermes/hermestest"
)

// TestAnalyzeThroughFakeHermes runs one job through HermesAnalyst against the fake gateway (no real
// Hermes): profile-scoped ticket, tool-less create, finite submit, message.complete, delete.
func TestAnalyzeThroughFakeHermes(t *testing.T) {
	f := hermestest.New(t)
	var mu sync.Mutex
	var methods []string
	f.Mu.Lock()
	f.Profiles = []string{AnalystProfile}
	f.Script = func(p *hermestest.Peer) {
		for {
			raw, err := p.Read()
			if err != nil {
				return
			}
			var r struct {
				ID     json.RawMessage `json:"id"`
				Method string          `json:"method"`
				Params map[string]any  `json:"params"`
			}
			if json.Unmarshal(raw, &r) != nil || r.Method == "" {
				continue
			}
			mu.Lock()
			methods = append(methods, r.Method)
			mu.Unlock()
			res := `{}`
			var after string
			switch r.Method {
			case "session.create":
				res = `{"session_id":"local-z","revision":1,"execution_generation":1,"info":{"tool_count":0,"model":"claude-opus-5-5"}}`
			case "prompt.submit":
				res = `{"admission_id":"adm-z","status":"started","execution_generation":2}`
				text, _ := json.Marshal(goodReply("TSTA"))
				after = fmt.Sprintf(`{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"local-z",`+
					`"admission_id":"adm-z","execution_generation":2,"payload":{"text":%s,"status":"complete"}}}`, text)
			}
			if p.Write(fmt.Sprintf(`{"jsonrpc":"2.0","id":%s,"result":%s}`, r.ID, res)) != nil {
				return
			}
			if after != "" && p.Write(after) != nil {
				return
			}
		}
	}
	f.Mu.Unlock()
	conn, err := hermes.New(hermes.Options{Home: f.Home})
	if err != nil {
		t.Fatal(err)
	}
	h := analyzerHarness(t, nil)
	h.analyst = HermesAnalyst(conn)
	h.start()
	h.expect("POST", "/v1/stocks/analyze/TSTA", `{"lang":"en"}`, 202, "")
	got := h.waitState("TSTA")
	if got["state"] != JobDone || got["result"].(map[string]any)["model"] != "claude-opus-5-5" {
		t.Fatalf("GET = %v", got)
	}
	mu.Lock()
	defer mu.Unlock()
	if strings.Join(methods, ",") != "session.create,prompt.submit,session.mutate" {
		t.Fatalf("methods = %v", methods)
	}
	f.Mu.Lock()
	defer f.Mu.Unlock()
	if len(f.Minted) != 1 || f.Minted[0].Scope != "profile" || !strings.HasSuffix(f.Minted[0].ProfileID, "/profiles/"+AnalystProfile) {
		t.Fatalf("minted = %+v", f.Minted)
	}
}
