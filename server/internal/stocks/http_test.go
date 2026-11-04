package stocks

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestWatchlistEndpoints(t *testing.T) {
	h := newHarness(t)
	if got := h.expect("GET", "/v1/stocks/watchlist", "", 200, ""); len(got["items"].([]any)) != 0 {
		t.Fatalf("empty = %v", got)
	}
	got := h.expect("PUT", "/v1/stocks/watchlist/bbca", "", 200, "")
	h.expect("PUT", "/v1/stocks/watchlist/BBCA", "", 200, "") // idempotent
	items := got["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["name"] != "Bank Central Asia Tbk" {
		t.Fatalf("after add = %v", got)
	}
	if _, c, _ := h.prov.calls(); c != 0 {
		t.Fatal("a bundled code must not hit the source")
	}
	got = h.expect("PUT", "/v1/stocks/watchlist/XYZA", "", 200, "") // not bundled: checked at the source
	if codesOf(t, got["items"])[1] != "XYZA" || got["items"].([]any)[1].(map[string]any)["name"] != "Xyz Abadi Tbk" {
		t.Fatalf("source-named add = %v", got)
	}
	h.expect("PUT", "/v1/stocks/watchlist/ZZZQ", "", 422, "unknown_symbol")
	h.expect("PUT", "/v1/stocks/watchlist/BBCA.JK", "", 400, "bad_code")
	h.expect("PUT", "/v1/stocks/watchlist/BB1", "", 400, "bad_code")
	h.expect("DELETE", "/v1/stocks/watchlist/TLKM", "", 404, "not_in_watchlist")
	h.expect("PUT", "/v1/stocks/watchlist/TLKM", "", 200, "")

	got = h.expect("PUT", "/v1/stocks/watchlist", `{"codes":["tlkm","BBCA","XYZA"]}`, 200, "")
	if fmt.Sprint(codesOf(t, got["items"])) != "[TLKM BBCA XYZA]" {
		t.Fatalf("reorder = %v", got)
	}
	for _, body := range []string{`{"codes":["TLKM","BBCA"]}`, `{"codes":["TLKM","BBCA","ANTM"]}`, `{"codes":[]}`, `{}`, `{"codes":["TLKM","BBCA","XYZA"],"x":1}`, `[`} {
		h.expect("PUT", "/v1/stocks/watchlist", body, 400, "bad_request")
	}
	got = h.expect("DELETE", "/v1/stocks/watchlist/bbca", "", 200, "")
	if fmt.Sprint(codesOf(t, got["items"])) != "[TLKM XYZA]" {
		t.Fatalf("delete = %v", got)
	}
	// Persisted: a new service on the same dir sees the same list.
	h.start()
	if got := h.expect("GET", "/v1/stocks/watchlist", "", 200, ""); fmt.Sprint(codesOf(t, got["items"])) != "[TLKM XYZA]" {
		t.Fatalf("reloaded = %v", got)
	}
}

func TestWatchlistLimitAndBlockedCheck(t *testing.T) {
	h := newHarness(t)
	st := DefaultState()
	for i := range MaxWatchlist {
		st, _ = st.addWatch(Entry{Code: code4(i), Name: "x"})
	}
	if err := saveState(h.dir, st); err != nil {
		t.Fatal(err)
	}
	h.start()
	h.expect("PUT", "/v1/stocks/watchlist/BBCA", "", 409, "limit_reached")
	h.expect("PUT", "/v1/stocks/watchlist/AAAB", "", 200, "") // already listed: fine at the limit

	h2 := newHarness(t)
	h2.prov.setErr(&BlockedError{})
	_, got, rec := h2.do("PUT", "/v1/stocks/watchlist/XYZA", "")
	if rec.Code != 503 || got["error"] != "upstream_blocked" || got["retry_after_s"] != 60.0 || rec.Header().Get("Retry-After") != "60" {
		t.Fatalf("blocked = %d %v %v", rec.Code, got, rec.Header())
	}
	h2.prov.setErr(&UpstreamError{Reason: "network"})
	h2.clock.Add(61e9)
	h2.expect("PUT", "/v1/stocks/watchlist/XYZA", "", 502, "upstream_error")
}

func TestHoldingsEndpoints(t *testing.T) {
	h := newHarness(t)
	got := h.expect("PUT", "/v1/stocks/holdings/bbca", `{"lots":10,"avg_price":4800}`, 200, "")
	row := got["holdings"].([]any)[0].(map[string]any)
	want := map[string]any{"code": "BBCA", "name": "Bank Central Asia Tbk", "lots": 10.0, "avg_price": 4800.0, "price": 4930.0,
		"cost": 4807200.0, "value": 4917675.0, "pl": 110475.0, "pl_pct": 2.3, "today": 90000.0, "stale": false}
	for k, v := range want {
		if row[k] != v {
			t.Errorf("%s = %v, want %v", k, row[k], v)
		}
	}
	if tot := got["total"].(map[string]any); tot["cost"] != 4807200.0 || tot["pl"] != 110475.0 || tot["pl_pct"] != 2.3 || tot["today"] != 90000.0 {
		t.Fatalf("total = %v", tot)
	}
	if got["at"] != "2026-11-05T10:00:00+07:00" {
		t.Fatalf("at = %v", got["at"])
	}
	// Replace (idempotent PUT) keeps one row.
	got = h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":5,"avg_price":4812.506}`, 200, "")
	if rows := got["holdings"].([]any); len(rows) != 1 || rows[0].(map[string]any)["avg_price"] != 4812.51 {
		t.Fatalf("replace = %v", rows)
	}
	for _, body := range []string{`{"lots":0,"avg_price":1}`, `{"lots":1.5,"avg_price":1}`, `{"lots":10000001,"avg_price":1}`,
		`{"lots":1,"avg_price":0}`, `{"lots":1}`, `{"avg_price":1}`, `{"lots":1,"avg_price":1,"fee":2}`, `{"lots":"1","avg_price":1}`} {
		h.expect("PUT", "/v1/stocks/holdings/ANTM", body, 400, "bad_request")
	}
	h.expect("PUT", "/v1/stocks/holdings/ZZZQ", `{"lots":1,"avg_price":1}`, 422, "unknown_symbol")
	// A code on the watchlist is named from it: no source call.
	h.expect("PUT", "/v1/stocks/watchlist/XYZA", "", 200, "")
	_, before, _ := h.prov.calls()
	got = h.expect("PUT", "/v1/stocks/holdings/XYZA", `{"lots":1,"avg_price":10}`, 200, "")
	if _, after, _ := h.prov.calls(); after != before || got["holdings"].([]any)[1].(map[string]any)["name"] != "Xyz Abadi Tbk" {
		t.Fatalf("holding named from the watchlist: chart calls %d→%d, %v", before, after, got["holdings"])
	}
	h.expect("DELETE", "/v1/stocks/holdings/XYZA", "", 200, "")
	h.expect("DELETE", "/v1/stocks/holdings/ANTM", "", 404, "not_held")

	// Prices unavailable: 200 with nulls, totals unknown.
	h.expect("PUT", "/v1/stocks/holdings/ANTM", `{"lots":2,"avg_price":3000}`, 200, "")
	h.prov.setErr(&BlockedError{})
	h.clock.Add(61e9)
	got = h.expect("GET", "/v1/stocks/holdings", "", 200, "")
	rows := got["holdings"].([]any)
	if r := rows[0].(map[string]any); r["stale"] != true || r["price"] != 4930.0 {
		t.Fatalf("stale cached price = %v", r)
	}
	h2 := newHarness(t)
	h2.expect("PUT", "/v1/stocks/holdings/ANTM", `{"lots":2,"avg_price":3000}`, 200, "")
	h2.prov.setErr(&UpstreamError{Reason: "x"})
	h2.svc.market = &Market{Src: h2.prov, Now: h2.clock.Now} // empty cache
	got = h2.expect("GET", "/v1/stocks/holdings", "", 200, "")
	r := got["holdings"].([]any)[0].(map[string]any)
	if r["price"] != nil || r["value"] != nil || r["pl"] != nil || r["cost"] != 600900.0 || got["total"].(map[string]any)["value"] != nil {
		t.Fatalf("no price = %v", got)
	}
	got = h2.expect("DELETE", "/v1/stocks/holdings/antm", "", 200, "")
	if len(got["holdings"].([]any)) != 0 || got["total"].(map[string]any)["cost"] != 0.0 {
		t.Fatalf("after delete = %v", got)
	}
}

func TestHoldingsLimit(t *testing.T) {
	h := newHarness(t)
	st := DefaultState()
	for i := range MaxHoldings {
		st, _ = st.putHolding(Holding{Code: code4(i), Name: "x", Lots: 1, AvgPrice: 1})
	}
	if err := saveState(h.dir, st); err != nil {
		t.Fatal(err)
	}
	h.start()
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":1,"avg_price":1}`, 409, "limit_reached")
	h.expect("PUT", "/v1/stocks/holdings/AAAA", `{"lots":3,"avg_price":2}`, 200, "")
}

func TestSettingsEndpoints(t *testing.T) {
	h := newHarness(t)
	if got := h.expect("GET", "/v1/stocks/settings", "", 200, ""); got["buy_fee_pct"] != 0.15 || got["sell_fee_pct"] != 0.25 {
		t.Fatalf("defaults = %v", got)
	}
	if got := h.expect("PUT", "/v1/stocks/settings", `{"buy_fee_pct":0.2,"sell_fee_pct":0}`, 200, ""); got["buy_fee_pct"] != 0.2 || got["sell_fee_pct"] != 0.0 {
		t.Fatalf("put = %v", got)
	}
	for _, body := range []string{`{"buy_fee_pct":5.01,"sell_fee_pct":0}`, `{"buy_fee_pct":-1,"sell_fee_pct":0}`, `{"buy_fee_pct":1}`, `{}`, "x"} {
		h.expect("PUT", "/v1/stocks/settings", body, 400, "bad_request")
	}
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":10,"avg_price":4800}`, 200, "")
	got := h.expect("GET", "/v1/stocks/holdings", "", 200, "")
	if row := got["holdings"].([]any)[0].(map[string]any); row["cost"] != 4809600.0 || row["value"] != 4930000.0 {
		t.Fatalf("fees not applied: %v", row)
	}
}

func TestQuotesEndpoint(t *testing.T) {
	h := newHarness(t)
	h.expect("PUT", "/v1/stocks/watchlist/BBCA", "", 200, "")
	h.expect("PUT", "/v1/stocks/holdings/ANTM", `{"lots":1,"avg_price":3000}`, 200, "")
	got := h.expect("GET", "/v1/stocks/quotes", "", 200, "")
	if fmt.Sprint(codesOf(t, got["quotes"])) != "[BBCA ANTM]" || len(got["errors"].([]any)) != 0 {
		t.Fatalf("default codes = %v", got)
	}
	m := got["market"].(map[string]any)
	if m["state"] != "open" || m["session"] != 1.0 || m["closes_at"] != "2026-11-05T12:00:00+07:00" || m["next_open"] != nil {
		t.Fatalf("market = %v", m)
	}
	q := got["quotes"].([]any)[0].(map[string]any)
	if q["price"] != 4930.0 || q["prev_close"] != 4840.0 || q["change"] != 90.0 || q["change_pct"] != 1.86 || q["tick"] != 10.0 ||
		q["ara"] != 6050.0 || q["arb"] != 4510.0 || q["stale"] != false || q["name"] != "Bank Central Asia Tbk" {
		t.Fatalf("quote = %v", q)
	}

	got = h.expect("GET", "/v1/stocks/quotes?codes=bbca,ZZZQ,BBCA", "", 200, "")
	errs := got["errors"].([]any)
	if len(got["quotes"].([]any)) != 1 || len(errs) != 1 || errs[0].(map[string]any)["code"] != "ZZZQ" || errs[0].(map[string]any)["error"] != "unknown_symbol" {
		t.Fatalf("mixed = %v", got)
	}
	h.expect("GET", "/v1/stocks/quotes?codes=BBCA,B", "", 400, "bad_code")
	many := make([]string, 51)
	for i := range many {
		many[i] = code4(i)
	}
	h.expect("GET", "/v1/stocks/quotes?codes="+strings.Join(many, ","), "", 400, "bad_request")

	h.prov.setErr(&BlockedError{})
	_, got, rec := h.do("GET", "/v1/stocks/quotes?codes=TLKM", "")
	if rec.Code != 503 || got["error"] != "upstream_blocked" || got["retry_after_s"] != 60.0 {
		t.Fatalf("blocked no cache = %d %v", rec.Code, got)
	}
	h.clock.Add(61e9)
	got = h.expect("GET", "/v1/stocks/quotes?codes=BBCA,TLKM", "", 200, "") // stale BBCA still answers
	if q := got["quotes"].([]any)[0].(map[string]any); q["stale"] != true || got["errors"].([]any)[0].(map[string]any)["error"] != "upstream_blocked" {
		t.Fatalf("stale = %v", got)
	}
	h2 := newHarness(t)
	h2.prov.setErr(&UpstreamError{Reason: "http 500"})
	h2.expect("GET", "/v1/stocks/quotes?codes=BBCA", "", 502, "upstream_error")
	if got := h2.expect("GET", "/v1/stocks/quotes", "", 200, ""); len(got["quotes"].([]any)) != 0 {
		t.Fatalf("empty lists = %v", got)
	}
}

func TestChartEndpoint(t *testing.T) {
	h := newHarness(t)
	got := h.expect("GET", "/v1/stocks/chart/bbca?range=5d", "", 200, "")
	if got["code"] != "BBCA" || got["range"] != "5d" || got["interval"] != "15m" || got["tz"] != "Asia/Jakarta" ||
		got["prev_close"] != 4840.0 || got["stale"] != false || len(got["bars"].([]any)) != 1 || got["name"] != nil {
		t.Fatalf("chart = %v", got)
	}
	if got := h.expect("GET", "/v1/stocks/chart/BBCA", "", 200, ""); got["range"] != "1d" || got["interval"] != "5m" {
		t.Fatalf("default range = %v", got)
	}
	h.expect("GET", "/v1/stocks/chart/BBCA?range=2y", "", 400, "bad_request")
	h.expect("GET", "/v1/stocks/chart/BBCA.JK", "", 400, "bad_code")
	h.expect("GET", "/v1/stocks/chart/ZZZQ", "", 422, "unknown_symbol")
	h.prov.setErr(&BlockedError{})
	h.clock.Add(61e9)
	if got := h.expect("GET", "/v1/stocks/chart/BBCA?range=5d", "", 200, ""); got["stale"] != true {
		t.Fatalf("stale = %v", got)
	}
	h.expect("GET", "/v1/stocks/chart/BBCA?range=5y", "", 503, "upstream_blocked")
}

func TestNewsEndpoint(t *testing.T) {
	h := newHarness(t)
	h.expect("PUT", "/v1/stocks/watchlist/PGEO", "", 200, "")
	got := h.expect("GET", "/v1/stocks/news", "", 200, "")
	items := got["items"].([]any)
	if len(items) != 22 || len(got["sources"].([]any)) != 3 {
		t.Fatalf("news = %d items, sources %v", len(items), got["sources"])
	}
	first := items[0].(map[string]any)
	for _, k := range []string{"id", "source", "title", "link", "published_at", "summary", "image_url", "codes", "match"} {
		if _, ok := first[k]; !ok {
			t.Errorf("missing %s in %v", k, first)
		}
	}
	pgeo := 0
	for _, it := range items {
		if m := it.(map[string]any); len(m["codes"].([]any)) > 0 {
			pgeo++
		}
	}
	if pgeo != 2 {
		t.Fatalf("PGEO items = %d", pgeo)
	}
	got = h.expect("GET", "/v1/stocks/news?code=antm&limit=5", "", 200, "") // a code not on any list still matches
	items = got["items"].([]any)
	if len(items) != 1 || items[0].(map[string]any)["match"] != "strong" || items[0].(map[string]any)["source"] != "kontan" {
		t.Fatalf("ANTM news = %v", items)
	}
	if got := h.expect("GET", "/v1/stocks/news?limit=3", "", 200, ""); len(got["items"].([]any)) != 3 {
		t.Fatal("limit")
	}
	h.expect("GET", "/v1/stocks/news?limit=0", "", 400, "bad_request")
	h.expect("GET", "/v1/stocks/news?limit=301", "", 400, "bad_request")
	h.expect("GET", "/v1/stocks/news?code=IHSG.JK", "", 400, "bad_code")
}

func TestSearchEndpoint(t *testing.T) {
	h := newHarness(t)
	got := h.expect("GET", "/v1/stocks/search?q=bca", "", 200, "")
	res := got["results"].([]any)
	if fmt.Sprint(codesOf(t, res)) != "[BBCA BCAP]" || res[0].(map[string]any)["in_universe"] != true || res[1].(map[string]any)["in_universe"] != false {
		t.Fatalf("search = %v", res)
	}
	h.expect("GET", "/v1/stocks/search?q=BBCA", "", 200, "")
	if _, _, s := h.prov.calls(); s != 1 {
		t.Fatalf("exact bundled code must not ask the source; calls = %d", s)
	}
	h.expect("GET", "/v1/stocks/search?q=", "", 400, "bad_request")
	h.expect("GET", "/v1/stocks/search?q="+strings.Repeat("a", 41), "", 400, "bad_request")
	h.prov.setErr(&BlockedError{})
	if got := h.expect("GET", "/v1/stocks/search?q=telkom", "", 200, ""); codesOf(t, got["results"])[0] != "TLKM" {
		t.Fatalf("blocked source still gives bundled rows: %v", got)
	}
}

func TestUnknownRouteAndMethod(t *testing.T) {
	h := newHarness(t)
	h.expect("GET", "/v1/stocks/nope", "", 404, "not_found")
	h.expect("POST", "/v1/stocks/watchlist", "", 404, "not_found")
}

// A stocks.json that cannot be read turns the state routes off (500) and is never overwritten;
// quotes for explicit codes keep working.
func TestBadStateFileFailsClosed(t *testing.T) {
	h := newHarness(t)
	path := filepath.Join(h.dir, stateFile)
	if err := os.WriteFile(path, []byte(`{"version":9}`), 0o600); err != nil {
		t.Fatal(err)
	}
	h.start()
	for _, r := range [][2]string{{"GET", "/v1/stocks/watchlist"}, {"PUT", "/v1/stocks/watchlist/BBCA"}, {"GET", "/v1/stocks/holdings"}, {"GET", "/v1/stocks/settings"}, {"GET", "/v1/stocks/quotes"}} {
		h.expect(r[0], r[1], "", http.StatusInternalServerError, "state_error")
	}
	h.expect("GET", "/v1/stocks/quotes?codes=BBCA", "", 200, "")
	if b, _ := os.ReadFile(path); string(b) != `{"version":9}` {
		t.Fatalf("state file rewritten: %s", b)
	}
	if !strings.Contains(h.logs.String(), "state unusable") {
		t.Fatalf("logs = %s", h.logs)
	}
}

// Holdings values (lots, prices, P/L) never reach the log, also on failures.
func TestLogsCarryNoHoldingValues(t *testing.T) {
	h := newHarness(t)
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":7531,"avg_price":4817}`, 200, "")
	h.prov.setErr(&BlockedError{})
	h.clock.Add(61e9)
	h.expect("GET", "/v1/stocks/holdings", "", 200, "")
	if err := os.Chmod(h.dir, 0o500); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(h.dir, 0o700)
	h.expect("PUT", "/v1/stocks/holdings/BBCA", `{"lots":7532,"avg_price":4818}`, 500, "state_error")
	logs := strings.ReplaceAll(h.logs.String(), h.dir, "<state dir>")
	for _, secret := range []string{"7531", "7532", "4817", "4818", "avg", "lots"} {
		if strings.Contains(logs, secret) {
			t.Fatalf("log carries %q: %s", secret, logs)
		}
	}
	if !strings.Contains(logs, "saving state failed") || !strings.Contains(logs, "backing off") {
		t.Fatalf("expected log lines missing: %s", logs)
	}
}
