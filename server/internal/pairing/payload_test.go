package pairing

import (
	"flag"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
)

var update = flag.Bool("update", false, "rewrite the golden files in testdata")

// The fixture the Android parser test reads too (android/app/src/test/.../PairPayloadTest.kt):
// one string both sides must agree on.
const payloadFixture = "testdata/pair-payload-v1.txt"

var (
	fixtureCode = "K7Q2M9XD"
	fixtureFP   = "7E54 D87B 0C1F 9A42 6B3E 11D0 8F5C 2A97 4E06 B1C8 D3F2 5A69 0E7B C4D1 BCA7 EAA7"
	fixtureURLs = []string{
		"https://192.168.1.61:8760",
		"https://10.0.0.2:8760",
		"https://100.74.61.96:8760",
		"https://[fd7a:115c:a1e0::d628:3d61]:8760",
	}
)

func TestPayloadMatchesTheSharedFixture(t *testing.T) {
	got, err := Payload(fixtureCode, fixtureFP, fixtureURLs)
	if err != nil {
		t.Fatal(err)
	}
	if *update {
		if err := os.WriteFile(payloadFixture, []byte(got+"\n"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(payloadFixture)
	if err != nil {
		t.Fatal(err)
	}
	if got != strings.TrimSuffix(string(want), "\n") {
		t.Fatalf("payload\n%s\nwant (%s)\n%s", got, filepath.Base(payloadFixture), want)
	}
}

// parse reads a payload back the way a phone would: the prefix, then the query.
func parse(t *testing.T, payload string) url.Values {
	t.Helper()
	query, ok := strings.CutPrefix(payload, PayloadPrefix)
	if !ok {
		t.Fatalf("payload %q lacks prefix %q", payload, PayloadPrefix)
	}
	v, err := url.ParseQuery(query)
	if err != nil {
		t.Fatalf("query %q: %v", query, err)
	}
	return v
}

func TestPayloadCarriesEveryAddressInOrderEscaped(t *testing.T) {
	got, err := Payload(fixtureCode, fixtureFP, fixtureURLs)
	if err != nil {
		t.Fatal(err)
	}
	v := parse(t, got)
	if !slices.Equal(v["a"], fixtureURLs) {
		t.Fatalf("addresses %q, want %q", v["a"], fixtureURLs)
	}
	if v.Get("v") != "1" || v.Get("c") != fixtureCode || v.Get("fp") != strings.ReplaceAll(fixtureFP, " ", "") {
		t.Fatalf("fields %v", v)
	}
	// Only the four documented keys: no token, no name, nothing else.
	keys := make([]string, 0, len(v))
	for k := range v {
		keys = append(keys, k)
	}
	slices.Sort(keys)
	if !slices.Equal(keys, []string{"a", "c", "fp", "v"}) {
		t.Fatalf("keys %q", keys)
	}
	// Every URL is query-escaped: no raw ':', '/', '[' or ']' after the prefix.
	if rest := strings.TrimPrefix(got, PayloadPrefix); strings.ContainsAny(rest, ":/[] ") {
		t.Fatalf("unescaped URL characters in %q", rest)
	}
	if !strings.HasPrefix(got, PayloadPrefix+"v=1&c="+fixtureCode+"&fp=") {
		t.Fatalf("field order: %q", got)
	}
}

func TestPayloadKeepsAtMostEightAddresses(t *testing.T) {
	var urls []string
	for i := range 11 {
		urls = append(urls, fmt.Sprintf("https://10.0.0.%d:8760", i+1))
	}
	got, err := Payload(fixtureCode, fixtureFP, urls)
	if err != nil {
		t.Fatal(err)
	}
	if a := parse(t, got)["a"]; !slices.Equal(a, urls[:MaxPayloadAddresses]) {
		t.Fatalf("addresses %q, want the first %d of %q", a, MaxPayloadAddresses, urls)
	}
}

func TestPayloadUppercasesAnUngroupedFingerprint(t *testing.T) {
	fp := strings.ToLower(strings.ReplaceAll(fixtureFP, " ", ""))
	got, err := Payload(fixtureCode, fp, fixtureURLs[:1])
	if err != nil {
		t.Fatal(err)
	}
	if parse(t, got).Get("fp") != strings.ToUpper(fp) {
		t.Fatalf("payload %q", got)
	}
}

func TestPayloadRefusesBadInput(t *testing.T) {
	tests := []struct {
		name, code, fp string
		urls           []string
	}{
		{"short code", "K7Q2M9X", fixtureFP, fixtureURLs},
		{"code outside the alphabet", "K7Q2M9XU", fixtureFP, fixtureURLs},
		{"lowercase code", "k7q2m9xd", fixtureFP, fixtureURLs},
		{"short fingerprint", fixtureCode, fixtureFP[:len(fixtureFP)-5], fixtureURLs},
		{"fingerprint not hex", fixtureCode, strings.Replace(fixtureFP, "7E54", "7G54", 1), fixtureURLs},
		{"no address", fixtureCode, fixtureFP, nil},
	}
	for _, tt := range tests {
		if got, err := Payload(tt.code, tt.fp, tt.urls); err == nil {
			t.Errorf("%s: payload %q, want an error", tt.name, got)
		}
	}
}
