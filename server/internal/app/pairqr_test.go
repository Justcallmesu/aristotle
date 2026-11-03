package app

import (
	"bytes"
	"fmt"
	"regexp"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/justcallmesu/aristotle/server/internal/httpapi"
	"github.com/justcallmesu/aristotle/server/internal/listen"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

const testFingerprint = "7E54 D87B 0C1F 9A42 6B3E 11D0 8F5C 2A97 4E06 B1C8 D3F2 5A69 0E7B C4D1 BCA7 EAA7"

var ansi = regexp.MustCompile("\x1b\\[[0-9;]*m")

// qrLines returns the QR code's lines of pair output (the ones that set colours), escapes removed.
func qrLines(out string) []string {
	var lines []string
	for _, l := range strings.Split(out, "\n") {
		if strings.HasPrefix(l, "\x1b[") {
			lines = append(lines, ansi.ReplaceAllString(l, ""))
		}
	}
	return lines
}

func pairResult(urls ...string) pairStartResult {
	res := pairStartResult{Code: "K7Q2M9XD", ExpiresAt: time.Now().Add(pairing.TTL), Fingerprint: testFingerprint}
	for _, u := range urls {
		res.Addresses = append(res.Addresses, pairAddr{Address: httpapi.Address{URL: u, Kind: "lan", Iface: "wlan0"}})
	}
	return res
}

func printed(res pairStartResult, withQR bool) string {
	var buf bytes.Buffer
	printPairing(&buf, res, time.Now(), withQR)
	return buf.String()
}

func TestPairPrintsTheQRCodeFirstThenTheManualData(t *testing.T) {
	out := printed(pairResult("https://192.168.1.61:8760", "https://100.74.61.96:8760"), true)
	lines := qrLines(out)
	if len(lines) < 10 {
		t.Fatalf("no QR code in\n%s", out)
	}
	if strings.Index(out, "\x1b[") > strings.Index(out, "Pairing code:") {
		t.Fatalf("QR code after the manual data:\n%s", out)
	}
	for _, want := range []string{"Pairing code:  K7Q2M9XD", testFingerprint, "https://192.168.1.61:8760", "Or type it in"} {
		if !strings.Contains(out, want) {
			t.Errorf("output missing %q", want)
		}
	}
	if strings.Contains(out, "first ") {
		t.Errorf("two addresses fit; no left-out note expected:\n%s", out)
	}
}

func TestNoQRPrintsOnlyTheManualData(t *testing.T) {
	res := pairResult("https://192.168.1.61:8760")
	without, with := printed(res, false), printed(res, true)
	if strings.ContainsAny(without, "\x1b█▀▄") || strings.Contains(without, "Scan") {
		t.Fatalf("QR code printed with --no-qr:\n%s", without)
	}
	if !strings.HasSuffix(with, without) {
		t.Fatalf("manual part differs with and without the QR code:\n%s\n---\n%s", with, without)
	}
}

func TestNoAddressNoQRCode(t *testing.T) {
	if out := printed(pairResult(), true); len(qrLines(out)) != 0 || !strings.Contains(out, "none:") {
		t.Fatalf("output:\n%s", out)
	}
}

// Eight long IPv6 addresses do not fit 80 columns: the last ones are left out (the
// pair response gives the phone the whole list) and the code stays scannable.
func TestQRCodeAlwaysFitsEightyColumns(t *testing.T) {
	var urls []string
	for i := range 10 {
		urls = append(urls, fmt.Sprintf("https://[fd7a:115c:a1e0:ab12:4843:cd96:626b:%04x]:8760", i))
	}
	out := printed(pairResult(urls...), true)
	lines := qrLines(out)
	if len(lines) == 0 {
		t.Fatalf("no QR code:\n%s", out)
	}
	for i, l := range lines {
		if n := utf8.RuneCountInString(l); n > qrMaxColumns {
			t.Fatalf("QR line %d is %d columns:\n%s", i, n, out)
		}
	}
	m := regexp.MustCompile(`carries the first ([1-7]) of 10 addresses`).FindStringSubmatch(out)
	if m == nil {
		t.Fatalf("want a note that fewer than 8 of 10 addresses are in the code:\n%s", out)
	}
}

func TestQRCodeTakesAtMostEightAddresses(t *testing.T) {
	var urls []string
	for i := range 9 {
		urls = append(urls, fmt.Sprintf("https://10.0.0.%d:8760", i+1))
	}
	if out := printed(pairResult(urls...), true); !strings.Contains(out, "carries the first 8 of 9 addresses") {
		t.Fatalf("output:\n%s", out)
	}
}

// End to end: pair prints the QR code on its stdout; --no-qr leaves it out; the server log has neither the
// code nor the payload.
func TestPairQROnStdoutOnly(t *testing.T) {
	ifaces := []listen.Iface{{Name: "wlan0", Up: true, Physical: true, DefaultRoute: true, Addrs: prefixes("192.168.1.61/24")}}
	srv, logs := startTestServerWith(t, func() ([]listen.Iface, error) { return ifaces, nil })
	for deadline := time.Now().Add(5 * time.Second); len(srv.listen.Active()) < 1 && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	code, out, errOut := run(t, "pair")
	if code != 0 || len(qrLines(out)) == 0 {
		t.Fatalf("pair exit %d (%s), no QR code:\n%s", code, errOut, out)
	}
	pairCode := regexp.MustCompile(`Pairing code:\s+([0-9A-Z]{8})`).FindStringSubmatch(out)[1]
	code, out, errOut = run(t, "pair", "--no-qr")
	if code != 0 || len(qrLines(out)) != 0 || !strings.Contains(out, "Pairing code:") {
		t.Fatalf("pair --no-qr exit %d (%s):\n%s", code, errOut, out)
	}
	secondCode := regexp.MustCompile(`Pairing code:\s+([0-9A-Z]{8})`).FindStringSubmatch(out)[1]
	all := logs.String()
	for _, secret := range []string{pairCode, secondCode, "aristotle://", "█", "▀"} {
		if strings.Contains(all, secret) {
			t.Fatalf("server log contains %q:\n%s", secret, all)
		}
	}
	if code, _, errOut = run(t, "pair", "--no-qr", "extra"); code != 2 || !strings.Contains(errOut, "--no-qr") {
		t.Fatalf("extra argument: exit %d %q", code, errOut)
	}
}
