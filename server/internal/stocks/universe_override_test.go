package stocks

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUniverseDefaultIsBundled(t *testing.T) {
	h := newHarness(t)
	got := h.expect("GET", "/v1/stocks/universe", "", 200, "")
	if got["source"] != "bundled" || got["count"] != float64(80) || len(got["lq45"].([]any)) != 45 || len(got["idx80"].([]any)) != 80 ||
		got["valid_to"] != "2027-01-29" || got["valid_from"] != "2026-11-02" || got["expired"] != false || got["file_error"] != nil {
		t.Fatalf("universe = %v", got)
	}
	if m := h.svc.uni().Members(); len(m) != 80 || m[0] != "AADI" {
		t.Fatalf("members = %v", m)
	}
}

func TestUniversePutValidatesSavesAndSwitches(t *testing.T) {
	h := newHarness(t)
	got := h.expect("PUT", "/v1/stocks/universe",
		`{"lq45":["bbca"," TLKM "],"idx80":["BBCA","TLKM","ABCD","ABCD"],"valid_from":"2027-02-01","valid_to":"2027-04-30"}`, 200, "")
	if got["source"] != "file" || got["count"] != float64(3) || fmt.Sprint(got["lq45"]) != "[BBCA TLKM]" ||
		fmt.Sprint(got["idx80"]) != "[ABCD BBCA TLKM]" || got["valid_to"] != "2027-04-30" || got["file_error"] != nil {
		t.Fatalf("put = %v", got)
	}
	u := h.svc.uni()
	if fmt.Sprint(u.Members()) != "[ABCD BBCA TLKM]" || !u.InIndex("ABCD") || u.InIndex("ANTM") {
		t.Fatalf("members = %v", u.Members())
	}
	if _, named := u.Lookup("ABCD"); named {
		t.Fatal("an unnamed member must not name a code (the source names it)")
	}
	if s, ok := u.Lookup("ANTM"); !ok || s.InIndex() || s.Name == "" {
		t.Fatalf("ANTM = %+v (still searchable, out of the index)", s) // names and search stay bundled
	}
	if r := h.expect("GET", "/v1/stocks/search?q=antm", "", 200, "")["results"].([]any); r[0].(map[string]any)["in_universe"] != false {
		t.Fatalf("search = %v", r)
	}
	data, err := os.ReadFile(filepath.Join(h.dir, universeFile))
	if err != nil || !strings.Contains(string(data), `"version": 1`) || !strings.Contains(string(data), `"updated_at": "2026-11-05T10:00:00+07:00"`) {
		t.Fatalf("file = %s, %v", data, err)
	}
	if fi, _ := os.Stat(filepath.Join(h.dir, universeFile)); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode = %v", fi.Mode())
	}

	// Kept across a restart.
	h.svc.Close()
	h.start()
	if got := h.expect("GET", "/v1/stocks/universe", "", 200, ""); got["source"] != "file" || got["count"] != float64(3) {
		t.Fatalf("after restart = %v", got)
	}

	// DELETE goes back to the bundled list.
	got = h.expect("DELETE", "/v1/stocks/universe", "", 200, "")
	if got["source"] != "bundled" || got["count"] != float64(80) {
		t.Fatalf("delete = %v", got)
	}
	if _, err := os.Stat(filepath.Join(h.dir, universeFile)); !os.IsNotExist(err) {
		t.Fatalf("file left: %v", err)
	}
	h.expect("DELETE", "/v1/stocks/universe", "", 200, "") // idempotent
}

func TestUniversePutRefusals(t *testing.T) {
	h := newHarness(t)
	var many []string
	for i := range 201 {
		many = append(many, fmt.Sprintf(`"%c%c%cA"`, 'A'+i/26/26%26, 'A'+i/26%26, 'A'+i%26))
	}
	two00 := strings.Join(many[:200], ",")
	h.expect("PUT", "/v1/stocks/universe", `{"lq45":[],"idx80":[`+two00+`],"valid_to":"2027-01-29"}`, 200, "")
	for body, code := range map[string]string{
		`{"lq45":["BBCA.JK"],"idx80":[],"valid_to":"2027-01-29"}`:                                                "bad_code",
		`{"lq45":["BBC1"],"idx80":[],"valid_to":"2027-01-29"}`:                                                   "bad_code",
		`{"lq45":[],"idx80":[` + strings.Join(many, ",") + `],"valid_to":"2027-01-29"}`:                          "bad_request",
		`{"lq45":["` + "AAAB" + `"],"idx80":[` + two00 + `],"valid_to":"2027-01-29"}`:                            "bad_request", // 201 over both lists
		`{"lq45":[],"idx80":[],"valid_to":"2027-01-29"}`:                                                         "bad_request",
		`{"lq45":["BBCA"],"idx80":[]}`:                                                                           "bad_request",
		`{"lq45":["BBCA"],"idx80":[],"valid_to":"29-01-2027"}`:                                                   "bad_request",
		`{"lq45":["BBCA"],"idx80":[],"valid_from":"2027-02-01","valid_to":"2027-01-29"}`:                         "bad_request",
		`{"lq45":["BBCA"],"valid_to":"2027-01-29"}`:                                                              "bad_request",
		`{"lq45":["BBCA"],"idx80":[],"valid_to":"2027-01-29","extra":1}`:                                         "bad_request",
		`{"lq45":["BBCA"],"idx80":[],"valid_to":"2027-01-29"} {}`:                                                "bad_request",
		`{"lq45":["BBCA"],"idx80":[],"valid_to":"2027-01-29","valid_from":"` + strings.Repeat("x", 17000) + `"}`: "bad_request",
	} {
		h.expect("PUT", "/v1/stocks/universe", body, 400, code)
	}
	if got := h.expect("GET", "/v1/stocks/universe", "", 200, ""); got["count"] != float64(200) {
		t.Fatalf("a refused PUT changed the list: %v", got["count"])
	}
}

func TestUniverseFileAtStart(t *testing.T) {
	h := newHarness(t)
	write := func(s string) {
		if err := os.WriteFile(filepath.Join(h.dir, universeFile), []byte(s), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	write(`{"version":1,"valid_to":"2026-10-31","lq45":["BBCA"],"idx80":["BBCA","BBRI"]}`)
	h.svc.Close()
	h.start()
	got := h.expect("GET", "/v1/stocks/universe", "", 200, "")
	if got["source"] != "file" || got["count"] != float64(2) || got["expired"] != true || got["valid_from"] != nil {
		t.Fatalf("file universe = %v", got)
	}
	if !strings.Contains(h.logs.String(), "past its period") {
		t.Fatalf("expiry not logged: %s", h.logs)
	}
	for _, bad := range []string{
		`{"version":1,"valid_to":"2027-01-29","lq45":["bbca!"],"idx80":[]}`,
		`{"version":2,"valid_to":"2027-01-29","lq45":["BBCA"],"idx80":[]}`,
		`{"version":1,"valid_to":"2027-01-29","lq45":["BBCA"],"idx80":[],"names":{}}`,
		`not json`,
	} {
		write(bad)
		h.logs.Reset()
		h.svc.Close()
		h.start()
		got := h.expect("GET", "/v1/stocks/universe", "", 200, "")
		if got["source"] != "bundled" || got["count"] != float64(80) || got["file_error"] != "invalid" ||
			!strings.Contains(h.logs.String(), "universe override unusable") {
			t.Fatalf("%s: universe = %v, logs %s", bad, got, h.logs)
		}
		if data, _ := os.ReadFile(filepath.Join(h.dir, universeFile)); string(data) != bad {
			t.Fatal("a bad override must be left alone")
		}
	}
}
