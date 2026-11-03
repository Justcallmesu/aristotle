package pairing

import (
	"errors"
	"net/url"
	"strconv"
	"strings"
)

// The pairing QR payload (protocol §11.4).
const (
	PayloadPrefix       = "aristotle://pair?"
	PayloadVersion      = 1
	MaxPayloadAddresses = 8
	fingerprintHexLen   = 64
)

// Payload is the text of the pairing QR code:
// aristotle://pair?v=1&c=<code>&fp=<64 hex>&a=<url>&a=<url>…, with the first
// MaxPayloadAddresses of urls in their order. It carries the one-time code and
// the certificate pin, never a device token. fingerprint may be grouped with
// spaces, as tlscert prints it.
func Payload(code, fingerprint string, urls []string) (string, error) {
	if len(code) != CodeLength || strings.Trim(code, Alphabet) != "" {
		return "", errors.New("pairing code is not 8 Crockford base32 characters")
	}
	fp := strings.ReplaceAll(fingerprint, " ", "")
	if len(fp) != fingerprintHexLen || strings.Trim(fp, "0123456789ABCDEFabcdef") != "" {
		return "", errors.New("certificate fingerprint is not 64 hex digits")
	}
	if len(urls) == 0 {
		return "", errors.New("no address to put in the QR code")
	}
	var b strings.Builder
	b.WriteString(PayloadPrefix)
	b.WriteString("v=" + strconv.Itoa(PayloadVersion))
	b.WriteString("&c=" + url.QueryEscape(code))
	b.WriteString("&fp=" + url.QueryEscape(strings.ToUpper(fp)))
	for _, u := range urls[:min(len(urls), MaxPayloadAddresses)] {
		b.WriteString("&a=" + url.QueryEscape(u))
	}
	return b.String(), nil
}
