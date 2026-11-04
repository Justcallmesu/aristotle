package stocks

import (
	"fmt"
	"testing"
)

func TestMatchArticle(t *testing.T) {
	bbca := Candidate{Code: "BBCA", Name: "Bank Central Asia Tbk", Aliases: []string{"BCA"}}
	antm := Candidate{Code: "ANTM", Name: "Aneka Tambang Tbk", Aliases: []string{"Antam"}}
	emas := Candidate{Code: "EMAS", Name: "Merdeka Gold Resources Tbk"}
	bumi := Candidate{Code: "BUMI", Name: "Bumi Resources Tbk"}
	cash := Candidate{Code: "CASH", Name: "Cashlez Worldwide Indonesia Tbk"}
	mine := Candidate{Code: "MINE", Name: "Sinar Terang Mandiri Tbk"}
	tins := Candidate{Code: "TINS", Name: "Timah Tbk"}
	ihsg := Candidate{Code: "IHSG", Name: ""}
	msci := Candidate{Code: "MSCI", Name: ""}
	tests := []struct {
		name, title, summary string
		cands                []Candidate
		codes                string
		strength             string
	}{
		{"bare code is weak", "Margin dan Penjualan Feronikel Topang Kinerja ANTM, Simak Rekomendasi Sahamnya", "", []Candidate{antm}, "[ANTM]", MatchWeak},
		{"(CODE) is strong", "Topang Kinerja", "katalis positif bagi PT Aneka Tambang Tbk (ANTM).", []Candidate{antm}, "[ANTM]", MatchStrong},
		{"after saham is strong", "Asing borong saham BBCA", "", []Candidate{bbca}, "[BBCA]", MatchStrong},
		{"after emiten is strong", "Emiten BBCA bagi dividen", "", []Candidate{bbca}, "[BBCA]", MatchStrong},
		{"lower case code is no hit", "bbca naik", "", []Candidate{bbca}, "[]", ""},
		{"longer token is no hit", "BBCAX dan BBCA-W", "", []Candidate{bbca}, "[]", ""},
		{"full name is strong", "Laba Bank Central Asia naik", "", []Candidate{bbca}, "[BBCA]", MatchStrong},
		{"alias is weak", "Kredit BCA tumbuh", "", []Candidate{bbca}, "[BBCA]", MatchWeak},
		{"alias is case-sensitive", "bca mobile", "", []Candidate{bbca}, "[]", ""},
		{"IHSG never a code", "IHSG Dibayangi Rupiah (IHSG)", "saham IHSG", []Candidate{ihsg}, "[]", ""},
		{"MSCI never a code", "Rebalancing MSCI", "", []Candidate{msci}, "[]", ""},
		{"K-CASH is not CASH", "Makmur Perluas Layanan K-CASH KSEI Bagi Nasabah", "", []Candidate{cash}, "[]", ""},
		{"CASH as a word needs context", "CASH IS KING", "", []Candidate{cash}, "[]", ""},
		{"CASH in parentheses counts", "Cashlez (CASH) rugi", "", []Candidate{cash}, "[CASH]", MatchStrong},
		{"emas as a word", "Harga emas Antam naik, EMAS PERHIASAN laris", "", []Candidate{emas}, "[]", ""},
		{"EMAS after saham", "Saham EMAS melonjak", "", []Candidate{emas}, "[EMAS]", MatchStrong},
		{"bumi as a word", "Hari BUMI, bumi kita", "", []Candidate{bumi}, "[]", ""},
		{"BUMI by full name", "Utang Bumi Resources turun", "", []Candidate{bumi}, "[BUMI]", MatchStrong},
		{"MINE as a word", "Pemegang Saham Restui MINE Jaminkan Aset", "", []Candidate{mine}, "[]", ""},
		{"one-word name is weak", "Harga timah dunia naik", "", []Candidate{tins}, "[TINS]", MatchWeak},
		{"name inside a word is no hit", "Antamina mine", "", []Candidate{antm}, "[]", ""},
		{"several codes, strongest wins", "ANTM dan saham BBCA", "", []Candidate{bbca, antm}, "[BBCA ANTM]", MatchStrong},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			m := MatchArticle(tt.title, tt.summary, tt.cands)
			got := fmt.Sprint(m.Codes)
			if m.Codes == nil {
				got = "[]"
			}
			if got != tt.codes || m.Strength != tt.strength {
				t.Fatalf("got %s %q, want %s %q", got, m.Strength, tt.codes, tt.strength)
			}
		})
	}
}

func TestPlainName(t *testing.T) {
	for in, want := range map[string]string{
		"PT Bank Rakyat Indonesia (Persero) Tbk": "Bank Rakyat Indonesia",
		"Bank Central Asia Tbk.":                 "Bank Central Asia",
		"Timah Tbk":                              "Timah",
	} {
		if got := plainName(in); got != want {
			t.Errorf("plainName(%q) = %q", in, got)
		}
	}
}
