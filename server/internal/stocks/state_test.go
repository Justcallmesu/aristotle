package stocks

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestStateRoundTripAndMode(t *testing.T) {
	dir := t.TempDir()
	st, err := loadState(dir)
	if err != nil || !reflect.DeepEqual(st, DefaultState()) {
		t.Fatalf("missing file = %+v, %v", st, err)
	}
	st, _ = st.addWatch(Entry{Code: "BBCA", Name: "Bank Central Asia Tbk"})
	st, _ = st.putHolding(Holding{Code: "ANTM", Name: "Aneka Tambang Tbk", Lots: 3, AvgPrice: 3012.5})
	st, _ = st.setSettings(Settings{BuyFeePct: 0.1, SellFeePct: 0.2})
	if err := saveState(dir, st); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(filepath.Join(dir, stateFile))
	if err != nil || info.Mode().Perm() != 0o600 {
		t.Fatalf("stat = %v, %v", info, err)
	}
	got, err := loadState(dir)
	if err != nil || !reflect.DeepEqual(got, st) || got.Version != 1 {
		t.Fatalf("round trip = %+v, %v; want %+v", got, err, st)
	}
}

func TestLoadStateRefusesBadFiles(t *testing.T) {
	tests := map[string]string{
		"corrupt":        `{"version":1,`,
		"other version":  `{"version":2,"watchlist":[],"holdings":[],"settings":{}}`,
		"unknown field":  `{"version":1,"watchlist":[],"holdings":[],"settings":{},"extra":1}`,
		"bad code":       `{"version":1,"watchlist":[{"code":"bbca","name":""}],"holdings":[],"settings":{}}`,
		"repeated code":  `{"version":1,"watchlist":[{"code":"BBCA"},{"code":"BBCA"}],"holdings":[],"settings":{}}`,
		"lots zero":      `{"version":1,"watchlist":[],"holdings":[{"code":"BBCA","lots":0,"avg_price":1}],"settings":{}}`,
		"fee over limit": `{"version":1,"watchlist":[],"holdings":[],"settings":{"buy_fee_pct":6,"sell_fee_pct":0}}`,
	}
	for name, body := range tests {
		t.Run(name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, stateFile), []byte(body), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := loadState(dir); err == nil {
				t.Fatal("accepted")
			}
		})
	}
}

func TestWatchlistLimitIdempotenceAndReorder(t *testing.T) {
	st := DefaultState()
	var err error
	for i := range MaxWatchlist {
		if st, err = st.addWatch(Entry{Code: code4(i)}); err != nil {
			t.Fatal(i, err)
		}
	}
	if _, err := st.addWatch(Entry{Code: "ZZZZ"}); !errors.Is(err, errLimit) {
		t.Fatalf("51st = %v", err)
	}
	if again, err := st.addWatch(Entry{Code: code4(3)}); err != nil || len(again.Watchlist) != MaxWatchlist {
		t.Fatalf("re-add at the limit = %d, %v", len(again.Watchlist), err)
	}

	small := DefaultState()
	for _, c := range []string{"BBCA", "BBRI", "TLKM"} {
		small, _ = small.addWatch(Entry{Code: c})
	}
	re, err := small.reorderWatch([]string{"TLKM", "BBCA", "BBRI"})
	if err != nil || fmt.Sprint(codesOfEntries(re.Watchlist)) != "[TLKM BBCA BBRI]" {
		t.Fatalf("reorder = %v, %v", re.Watchlist, err)
	}
	if fmt.Sprint(codesOfEntries(small.Watchlist)) != "[BBCA BBRI TLKM]" {
		t.Fatal("reorder changed the original")
	}
	for name, codes := range map[string][]string{
		"missing one": {"TLKM", "BBCA"},
		"extra":       {"TLKM", "BBCA", "BBRI", "ANTM"},
		"swapped one": {"TLKM", "BBCA", "ANTM"},
		"repeated":    {"TLKM", "BBCA", "BBCA"},
		"empty":       {},
	} {
		if _, err := small.reorderWatch(codes); !errors.Is(err, errBadRequest) {
			t.Errorf("%s: %v", name, err)
		}
	}
	if _, err := small.removeWatch("ANTM"); !errors.Is(err, errNotInWatchlist) {
		t.Fatalf("remove absent = %v", err)
	}
}

func TestHoldingsLimitAndReplace(t *testing.T) {
	st := DefaultState()
	var err error
	for i := range MaxHoldings {
		if st, err = st.putHolding(Holding{Code: code4(i), Lots: 1, AvgPrice: 100}); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := st.putHolding(Holding{Code: "ZZZZ", Lots: 1, AvgPrice: 1}); !errors.Is(err, errLimit) {
		t.Fatalf("51st = %v", err)
	}
	st, err = st.putHolding(Holding{Code: code4(7), Lots: 9, AvgPrice: 250})
	if err != nil || len(st.Holdings) != MaxHoldings || st.Holdings[7].Lots != 9 || st.Holdings[7].AvgPrice != 250 {
		t.Fatalf("replace = %+v, %v", st.Holdings[7], err)
	}
	for _, h := range []Holding{{Code: "BBCA", Lots: 0, AvgPrice: 1}, {Code: "BBCA", Lots: MaxLots + 1, AvgPrice: 1}, {Code: "BBCA", Lots: 1, AvgPrice: 0}} {
		if _, err := DefaultState().putHolding(h); !errors.Is(err, errBadRequest) {
			t.Errorf("%+v = %v", h, err)
		}
	}
	if _, err := DefaultState().removeHolding("BBCA"); !errors.Is(err, errNotHeld) {
		t.Fatalf("remove absent = %v", err)
	}
}

// code4 makes distinct valid codes: AAAA, AAAB, …
func code4(i int) string {
	b := []byte("AAAA")
	for p := 3; p >= 0; p-- {
		b[p] = byte('A' + i%26)
		i /= 26
	}
	return string(b)
}

func codesOfEntries(es []Entry) []string {
	var out []string
	for _, e := range es {
		out = append(out, e.Code)
	}
	return out
}
