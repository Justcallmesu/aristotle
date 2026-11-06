package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"testing"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/stocks"
)

// /v1/stocks/* is mounted only with a handler, always behind the device token.
func TestStocksMountNeedsTokenAndHandler(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "")
	if rec := rm.do(http.MethodGet, "/v1/stocks/watchlist", "", p.Token); rec.Code != http.StatusNotFound {
		t.Fatalf("no handler = %d", rec.Code)
	}
	var paths []string
	stocks := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		writeJSON(w, http.StatusOK, map[string]any{"items": []any{}})
	})
	rm.h = NewRemoteRouter("t", slog.New(slog.NewTextHandler(rm.logs, nil)), Deps{Codes: rm.codes, Devices: rm.store, Stocks: stocks})
	if rec := rm.do(http.MethodGet, "/v1/stocks/watchlist", "", ""); rec.Code != http.StatusUnauthorized || len(paths) != 0 {
		t.Fatalf("no token = %d, handler saw %v", rec.Code, paths)
	}
	if rec := rm.do(http.MethodPut, "/v1/stocks/holdings/BBCA", `{"lots":1,"avg_price":1}`, "wrong"); rec.Code != http.StatusUnauthorized || len(paths) != 0 {
		t.Fatalf("bad token = %d", rec.Code)
	}
	for _, req := range [][2]string{{http.MethodGet, "/v1/stocks/watchlist"}, {http.MethodPut, "/v1/stocks/holdings/BBCA"}, {http.MethodDelete, "/v1/stocks/watchlist/BBCA"}} {
		if rec := rm.do(req[0], req[1], "", p.Token); rec.Code != http.StatusOK {
			t.Fatalf("%s %s = %d", req[0], req[1], rec.Code)
		}
	}
	if len(paths) != 3 || paths[1] != "PUT /v1/stocks/holdings/BBCA" {
		t.Fatalf("handler saw %v", paths)
	}
}

// offlineProvider fails every call: no test reaches Yahoo.
type offlineProvider struct{}

var errOffline = &stocks.UpstreamError{Reason: "offline in tests"}

func (offlineProvider) Quotes(context.Context, []string) (map[string]stocks.Quote, error) {
	return nil, errOffline
}
func (offlineProvider) Chart(context.Context, string, string) (stocks.Chart, error) {
	return stocks.Chart{}, errOffline
}
func (offlineProvider) Search(context.Context, string) ([]stocks.SearchResult, error) {
	return nil, errOffline
}
func (offlineProvider) ChartPeriod(context.Context, string, time.Time, time.Time, string) (stocks.Chart, error) {
	return stocks.Chart{}, errOffline
}

// TR1: the track record (protocol §20.5) is served by the real stocks service behind the device token.
func TestTrackRecordNeedsToken(t *testing.T) {
	rm := newRemote(t)
	p := rm.pair(t, "", "")
	svc := stocks.New(stocks.Options{StateDir: t.TempDir(), Provider: offlineProvider{},
		FetchFeed: func(context.Context, string) ([]byte, error) { return nil, errOffline }})
	t.Cleanup(svc.Close)
	rm.h = NewRemoteRouter("t", slog.New(slog.NewTextHandler(rm.logs, nil)), Deps{Codes: rm.codes, Devices: rm.store, Stocks: svc})
	for _, token := range []string{"", "wrong"} {
		if rec := rm.do(http.MethodGet, "/v1/stocks/track-record", "", token); rec.Code != http.StatusUnauthorized {
			t.Fatalf("token %q = %d", token, rec.Code)
		}
	}
	rec := rm.do(http.MethodGet, "/v1/stocks/track-record?code=BBCA", "", p.Token)
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); rec.Code != http.StatusOK || err != nil || body["pending"] != 0.0 {
		t.Fatalf("with token = %d %s", rec.Code, rec.Body)
	}
}
