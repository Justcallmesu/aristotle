package stocks

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestMarketQuotesTTL(t *testing.T) {
	clk := newClock("2026-11-05T10:00:00+07:00")
	prov := newFakeProvider()
	m := &Market{Src: prov, Now: clk.Now}
	ctx := context.Background()
	res := m.Quotes(ctx, []string{"BBCA", "ZZZQ"})
	if _, ok := res.Quotes["BBCA"]; !ok || !errors.Is(res.Errors["ZZZQ"], ErrUnknownSymbol) {
		t.Fatalf("first = %+v", res)
	}
	clk.Add(59 * time.Second)
	m.Quotes(ctx, []string{"BBCA"})
	if q, _, _ := prov.calls(); q != 1 {
		t.Fatalf("within TTL: %d source calls", q)
	}
	clk.Add(2 * time.Second)
	m.Quotes(ctx, []string{"BBCA"})
	if q, _, _ := prov.calls(); q != 2 {
		t.Fatalf("after TTL: %d source calls", q)
	}
}

// A 429 starts a backoff (1, 2, 4 … 15 min): no call goes out meanwhile, cached quotes come back stale,
// codes never fetched get the BlockedError with the time left.
func TestMarketBackoffServesStaleCache(t *testing.T) {
	clk := newClock("2026-11-05T10:00:00+07:00")
	prov := newFakeProvider()
	m := &Market{Src: prov, Now: clk.Now}
	ctx := context.Background()
	m.Quotes(ctx, []string{"BBCA"})

	prov.setErr(&BlockedError{})
	clk.Add(61 * time.Second)
	res := m.Quotes(ctx, []string{"BBCA", "ANTM"})
	var be *BlockedError
	if q := res.Quotes["BBCA"]; !q.Stale || q.Price != 4930 || !errors.As(res.Errors["ANTM"], &be) || be.RetryAfter != time.Minute {
		t.Fatalf("blocked = %+v errs %v", res.Quotes, res.Errors)
	}
	clk.Add(30 * time.Second)
	res = m.Quotes(ctx, []string{"ANTM"})
	if q, _, _ := prov.calls(); q != 2 || !errors.As(res.Errors["ANTM"], &be) || be.RetryAfter != 30*time.Second {
		t.Fatalf("during backoff: %d calls, %v", q, res.Errors)
	}
	clk.Add(31 * time.Second) // backoff over: one call, blocked again → 2 min
	m.Quotes(ctx, []string{"ANTM"})
	if q, _, _ := prov.calls(); q != 3 || m.blocked() != 2*time.Minute {
		t.Fatalf("second block: %d calls, backoff %s", q, m.blocked())
	}
	for _, want := range []time.Duration{4, 8, 15, 15} {
		clk.Add(m.blocked())
		m.Quotes(ctx, []string{"ANTM"})
		if m.blocked() != want*time.Minute {
			t.Fatalf("backoff = %s, want %d min", m.blocked(), want)
		}
	}
	prov.setErr(nil)
	clk.Add(m.blocked())
	if res := m.Quotes(ctx, []string{"ANTM", "BBCA"}); res.Quotes["ANTM"].Stale || res.Quotes["BBCA"].Stale || m.backoff != 0 {
		t.Fatalf("recovered = %+v backoff %s", res.Quotes, m.backoff)
	}
}

func TestMarketChartTTLAndStale(t *testing.T) {
	clk := newClock("2026-11-05T10:00:00+07:00")
	prov := newFakeProvider()
	m := &Market{Src: prov, Now: clk.Now}
	ctx := context.Background()
	for _, rng := range []string{"1d", "1y"} {
		if _, err := m.Chart(ctx, "BBCA", rng); err != nil {
			t.Fatal(err)
		}
	}
	clk.Add(2 * time.Minute) // intraday (60 s) expired, daily (15 min) not
	m.Chart(ctx, "BBCA", "1d")
	m.Chart(ctx, "BBCA", "1y")
	if _, c, _ := prov.calls(); c != 3 {
		t.Fatalf("chart calls = %d, want 3", c)
	}
	prov.setErr(&UpstreamError{Reason: "http 502"})
	clk.Add(16 * time.Minute)
	ch, err := m.Chart(ctx, "BBCA", "1y")
	if err != nil || !ch.Stale || len(ch.Bars) != 1 {
		t.Fatalf("stale chart = %+v, %v", ch, err)
	}
	if _, err := m.Chart(ctx, "BBCA", "5y"); err == nil {
		t.Fatal("no cache and a failing source must fail")
	}
	prov.setErr(nil)
	if _, err := m.Chart(ctx, "ZZZQ", "1d"); !errors.Is(err, ErrUnknownSymbol) {
		t.Fatalf("unknown = %v", err)
	}
}

func TestMarketSearchCached(t *testing.T) {
	clk := newClock("2026-11-05T10:00:00+07:00")
	prov := newFakeProvider()
	m := &Market{Src: prov, Now: clk.Now}
	m.Search(context.Background(), "bca")
	clk.Add(23 * time.Hour)
	m.Search(context.Background(), "BCA")
	if _, _, s := prov.calls(); s != 1 {
		t.Fatalf("search calls = %d", s)
	}
}
