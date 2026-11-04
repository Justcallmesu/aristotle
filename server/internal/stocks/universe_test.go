package stocks

import (
	"testing"
	"time"
)

func TestBundledUniverse(t *testing.T) {
	u := bundledUniverse()
	lq, idx := 0, 0
	for _, s := range u.Stocks {
		for _, tag := range s.Tags {
			switch tag {
			case "LQ45":
				lq++
				if !hasTag(s, "IDX80") {
					t.Errorf("%s in LQ45 but not IDX80", s.Code)
				}
			case "IDX80":
				idx++
			default:
				t.Errorf("%s: unknown tag %q", s.Code, tag)
			}
		}
	}
	if lq != 45 || idx != 80 {
		t.Fatalf("LQ45 = %d, IDX80 = %d", lq, idx)
	}
	// Peng-00205: ELSA and SMGR in LQ45, CUAN and SCMA out; SMDR in IDX80, HEAL/MIKA/SMRA/CUAN out.
	for code, want := range map[string]bool{"ELSA": true, "SMGR": true, "CUAN": false, "SCMA": false} {
		if s, _ := u.Lookup(code); hasTag(s, "LQ45") != want {
			t.Errorf("%s in LQ45 = %v", code, !want)
		}
	}
	for code, want := range map[string]bool{"SMDR": true, "AALI": true, "TINS": true, "TKIM": true, "HEAL": false, "MIKA": false, "SMRA": false, "CUAN": false} {
		if s, _ := u.Lookup(code); hasTag(s, "IDX80") != want {
			t.Errorf("%s in IDX80 = %v", code, !want)
		}
	}
	if u.Expired(time.Date(2027, 1, 29, 23, 0, 0, 0, WIB)) || !u.Expired(time.Date(2027, 1, 30, 0, 0, 0, 0, WIB)) {
		t.Fatal("valid_to 2027-01-29")
	}
}

func hasTag(s Stock, tag string) bool {
	for _, t := range s.Tags {
		if t == tag {
			return true
		}
	}
	return false
}

func TestUniverseSearch(t *testing.T) {
	u := bundledUniverse()
	tests := []struct{ q, first string }{
		{"bca", "BBCA"}, {"BBCA", "BBCA"}, {"telkom", "TLKM"}, {"antam", "ANTM"}, {"bank central", "BBCA"}, {"bsi", "BRIS"},
	}
	for _, tt := range tests {
		got := u.Search(tt.q, 20)
		if len(got) == 0 || got[0].Code != tt.first {
			t.Errorf("Search(%q) = %+v, want %s first", tt.q, got, tt.first)
		}
	}
	if got := u.Search("BB", 3); len(got) != 3 || got[0].Code[:2] != "BB" {
		t.Fatalf("prefix = %+v", got)
	}
	if got := u.Search("BRIS", 1); got[0].InUniverse {
		t.Fatal("BRIS is search-only, not in LQ45/IDX80")
	}
	if got := u.Search("qqqqq", 5); len(got) != 0 {
		t.Fatalf("nonsense = %+v", got)
	}
}
