package stocks

import (
	"strings"
	"unicode"
)

// Ticker matching (TS0 §4). Candidates are the user's codes (watchlist ∪ holdings, plus a code asked
// for). A code hits when a token equals it exactly, case-sensitive; tokens keep inner hyphens, so
// "K-CASH" is not CASH. "(CODE)" or a preceding "saham"/"emiten" makes the hit strong, else weak.
// A company's full name (without PT/Tbk/(Persero)), whole words, case-insensitive, is strong when it
// has two or more words, else weak; a bundled alias ("BCA", "Antam"), case-sensitive, is weak.

// Match strengths.
const (
	MatchStrong = "strong"
	MatchWeak   = "weak"
)

// notTickers are capitalised words in market news that are never stock codes.
var notTickers = map[string]bool{
	"IHSG": true, "CNBC": true, "MSCI": true, "SRBI": true, "BUMN": true, "KSSK": true, "APBN": true,
	"UMKM": true, "PLTS": true, "KSEI": true, "IDX": true, "LQ45": true, "FTSE": true, "NYSE": true,
}

// wordCodes are codes that are also ordinary words or acronyms (Indonesian or English); they count
// only in a strong context.
var wordCodes = map[string]bool{
	"CASH": true, "MINE": true, "KEEN": true, "ACES": true, "TOYS": true, "ENVY": true, "TELE": true,
	"BUMI": true, "EMAS": true, "BANK": true, "GOOD": true, "BEST": true, "FAST": true, "HOPE": true,
	"LIFE": true, "PURE": true, "SAFE": true, "WIFI": true, "BIKE": true, "BOLT": true, "CITY": true,
}

// Candidate is one code to look for, with its names.
type Candidate struct {
	Code    string
	Name    string
	Aliases []string
}

// ArticleMatch is the result for one article; Strength is "" when no code hit.
type ArticleMatch struct {
	Codes    []string
	Strength string
}

type token struct {
	text          string
	parenthesised bool
	prev          string // the previous token, lower case
}

func tokenize(s string) []token {
	isTok := func(r rune) bool { return unicode.IsLetter(r) || unicode.IsDigit(r) || r == '-' }
	var out []token
	rs := []rune(s)
	prev := ""
	for i := 0; i < len(rs); {
		if !isTok(rs[i]) {
			i++
			continue
		}
		j := i
		for j < len(rs) && isTok(rs[j]) {
			j++
		}
		text := strings.Trim(string(rs[i:j]), "-")
		paren := i > 0 && rs[i-1] == '(' && j < len(rs) && rs[j] == ')'
		if text != "" {
			out = append(out, token{text: text, parenthesised: paren, prev: prev})
			prev = strings.ToLower(text)
		}
		i = j
	}
	return out
}

// MatchArticle finds the candidates an article (title + summary) talks about.
func MatchArticle(title, summary string, cands []Candidate) ArticleMatch {
	text := title + " \n " + summary
	toks := tokenize(text)
	lower := strings.ToLower(text)
	var m ArticleMatch
	for _, c := range cands {
		strength := codeHit(c.Code, toks)
		if s := nameHit(c, text, lower); rankStrength(s) > rankStrength(strength) {
			strength = s
		}
		if strength == "" {
			continue
		}
		m.Codes = append(m.Codes, c.Code)
		if rankStrength(strength) > rankStrength(m.Strength) {
			m.Strength = strength
		}
	}
	return m
}

func rankStrength(s string) int {
	switch s {
	case MatchStrong:
		return 2
	case MatchWeak:
		return 1
	}
	return 0
}

func codeHit(code string, toks []token) string {
	if notTickers[code] {
		return ""
	}
	best := ""
	for _, t := range toks {
		if t.text != code {
			continue
		}
		if t.parenthesised || t.prev == "saham" || t.prev == "emiten" {
			return MatchStrong
		}
		if !wordCodes[code] {
			best = MatchWeak
		}
	}
	return best
}

// plainName is a company name without PT, Tbk and (Persero).
func plainName(name string) string {
	var keep []string
	for _, w := range strings.Fields(name) {
		switch strings.ToLower(w) {
		case "pt", "pt.", "tbk", "tbk.", "(persero)":
		default:
			keep = append(keep, w)
		}
	}
	return strings.Join(keep, " ")
}

func nameHit(c Candidate, text, lower string) string {
	if n := plainName(c.Name); n != "" && containsWord(lower, strings.ToLower(n)) {
		if len(strings.Fields(n)) >= 2 {
			return MatchStrong
		}
		return MatchWeak
	}
	for _, a := range c.Aliases {
		if a != "" && containsWord(text, a) {
			return MatchWeak
		}
	}
	return ""
}

// containsWord reports whether w occurs in s with no letter or digit right before or after it.
func containsWord(s, w string) bool {
	for i := 0; ; {
		j := strings.Index(s[i:], w)
		if j < 0 {
			return false
		}
		start, end := i+j, i+j+len(w)
		if !wordRuneBefore(s, start) && !wordRuneAt(s, end) {
			return true
		}
		i = start + 1
	}
}

func wordRuneBefore(s string, i int) bool {
	if i == 0 {
		return false
	}
	r := []rune(s[:i])
	return isWordRune(r[len(r)-1])
}

func wordRuneAt(s string, i int) bool {
	if i >= len(s) {
		return false
	}
	return isWordRune([]rune(s[i:])[0])
}

func isWordRune(r rune) bool { return unicode.IsLetter(r) || unicode.IsDigit(r) }
