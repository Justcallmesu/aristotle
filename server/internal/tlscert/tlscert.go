// Package tlscert creates and loads the server's self-signed TLS certificate.
//
// The phone pins the SHA-256 of the certificate's SubjectPublicKeyInfo (shown
// at pairing), not a hostname or IP. The SANs are filled with the LAN and
// Tailscale IPs seen on first start only as a courtesy; when the laptop gets
// a new IP the certificate is NOT regenerated, because the pin still matches.
// The same certificate serves every remote listener (spec Q49). The key pair
// is created once and reused, so the fingerprint stays stable across restarts.
package tlscert

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"io/fs"
	"math/big"
	"net"
	"net/netip"
	"path/filepath"
	"strings"
	"time"

	"github.com/justcallmesu/aristotle/server/internal/statefile"
)

const (
	CertFile = "tls-cert.pem"
	KeyFile  = "tls-key.pem"
	validity = 20 * 365 * 24 * time.Hour
)

// Cert is the loaded certificate and its pin.
type Cert struct {
	TLS         tls.Certificate
	Fingerprint string // SPKI SHA-256, uppercase hex in 4-char groups
}

// LoadOrCreate loads the certificate and key from stateDir, or creates them
// (ECDSA P-256, self-signed, SANs = sans) when neither file exists. A half
// pair (one file missing) or an unreadable pair is an error that names the
// file(s) to delete: regenerating silently would change the fingerprint
// every paired phone has pinned.
func LoadOrCreate(stateDir string, sans []netip.Addr, now time.Time) (Cert, bool, error) {
	certPEM, certErr := statefile.Read(stateDir, CertFile)
	keyPEM, keyErr := statefile.Read(stateDir, KeyFile)
	certMissing, keyMissing := errors.Is(certErr, fs.ErrNotExist), errors.Is(keyErr, fs.ErrNotExist)
	certPath, keyPath := filepath.Join(stateDir, CertFile), filepath.Join(stateDir, KeyFile)
	switch {
	case certMissing && keyMissing:
		c, err := create(stateDir, sans, now)
		return c, true, err
	case certMissing && keyErr == nil:
		return Cert{}, false, halfPair(keyPath, certPath)
	case keyMissing && certErr == nil:
		return Cert{}, false, halfPair(certPath, keyPath)
	}
	if err := errors.Join(certErr, keyErr); err != nil {
		return Cert{}, false, fmt.Errorf("load TLS certificate: %w", err)
	}
	c, err := parse(certPEM, keyPEM)
	if err != nil {
		return Cert{}, false, fmt.Errorf("%w; to start over delete both %s and %s (paired phones must re-pair)", err, certPath, keyPath)
	}
	return c, false, nil
}

// halfPair is the error for a pair where only one file exists, e.g. after a
// crash during the first start (the key is written before the certificate).
func halfPair(present, missing string) error {
	return fmt.Errorf("TLS key pair is incomplete: %s exists but %s is missing (interrupted first start?); "+
		"delete %s and start again to create a new pair (phones paired with the old one must re-pair)", present, missing, present)
}

func create(stateDir string, sans []netip.Addr, now time.Time) (Cert, error) {
	certPEM, keyPEM, err := generate(sans, now)
	if err != nil {
		return Cert{}, err
	}
	if err := statefile.WriteAtomic(stateDir, KeyFile, keyPEM); err != nil {
		return Cert{}, err
	}
	if err := statefile.WriteAtomic(stateDir, CertFile, certPEM); err != nil {
		return Cert{}, err
	}
	return parse(certPEM, keyPEM)
}

func generate(sans []netip.Addr, now time.Time) (certPEM, keyPEM []byte, err error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 127))
	if err != nil {
		return nil, nil, err
	}
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "aristotle-server"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(validity),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"aristotle-server"},
		IPAddresses:           toIPs(sans),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, nil, err
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	return certPEM, keyPEM, nil
}

func toIPs(addrs []netip.Addr) []net.IP {
	ips := make([]net.IP, 0, len(addrs))
	for _, a := range addrs {
		ips = append(ips, net.IP(a.Unmap().AsSlice()))
	}
	return ips
}

func parse(certPEM, keyPEM []byte) (Cert, error) {
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return Cert{}, fmt.Errorf("parse TLS certificate: %w", err)
	}
	return Cert{TLS: pair, Fingerprint: Fingerprint(pair.Leaf)}, nil
}

// Fingerprint returns SHA-256(SubjectPublicKeyInfo) as uppercase hex in
// space-separated groups of 4 characters.
func Fingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	hexStr := strings.ToUpper(hex.EncodeToString(sum[:]))
	groups := make([]string, 0, len(hexStr)/4)
	for i := 0; i < len(hexStr); i += 4 {
		groups = append(groups, hexStr[i:i+4])
	}
	return strings.Join(groups, " ")
}

// ServerConfig returns the TLS config for every remote (LAN and Tailscale) listener.
func (c Cert) ServerConfig() *tls.Config {
	return &tls.Config{Certificates: []tls.Certificate{c.TLS}, MinVersion: tls.VersionTLS12}
}
