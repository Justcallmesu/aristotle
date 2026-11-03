package tlscert

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"encoding/hex"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

var now = time.Date(2026, 11, 3, 10, 0, 0, 0, time.UTC)

func TestCreatedOnceThenReused(t *testing.T) {
	dir := t.TempDir()
	lan := netip.MustParseAddr("192.168.1.61")
	first, created, err := LoadOrCreate(dir, []netip.Addr{lan}, now)
	if err != nil || !created {
		t.Fatalf("first: created=%v err=%v", created, err)
	}
	for _, f := range []string{CertFile, KeyFile} {
		info, err := os.Stat(filepath.Join(dir, f))
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != 0o600 {
			t.Fatalf("%s mode = %v, want 0600", f, info.Mode().Perm())
		}
	}
	second, created, err := LoadOrCreate(dir, []netip.Addr{netip.MustParseAddr("10.0.0.9")}, now.Add(time.Hour))
	if err != nil || created {
		t.Fatalf("second: created=%v err=%v", created, err)
	}
	if first.Fingerprint != second.Fingerprint {
		t.Fatalf("fingerprint changed: %s vs %s", first.Fingerprint, second.Fingerprint)
	}
	leaf := second.TLS.Leaf
	if _, ok := leaf.PublicKey.(*ecdsa.PublicKey); !ok || leaf.PublicKey.(*ecdsa.PublicKey).Curve != elliptic.P256() {
		t.Fatalf("key is not ECDSA P-256: %T", leaf.PublicKey)
	}
	if len(leaf.IPAddresses) != 1 || !leaf.IPAddresses[0].Equal(lan.AsSlice()) {
		t.Fatalf("SANs = %v, want the first-start LAN IP only", leaf.IPAddresses)
	}
}

func TestFingerprintFormat(t *testing.T) {
	c, _, err := LoadOrCreate(t.TempDir(), nil, now)
	if err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^[0-9A-F]{4}( [0-9A-F]{4}){15}$`).MatchString(c.Fingerprint) {
		t.Fatalf("fingerprint %q", c.Fingerprint)
	}
	sum := sha256.Sum256(c.TLS.Leaf.RawSubjectPublicKeyInfo)
	if strings.ReplaceAll(c.Fingerprint, " ", "") != strings.ToUpper(hex.EncodeToString(sum[:])) {
		t.Fatal("fingerprint is not SHA-256 of the SPKI")
	}
}

func TestHalfPairNamesTheFileToDelete(t *testing.T) {
	for _, tt := range []struct{ removed, left string }{{CertFile, KeyFile}, {KeyFile, CertFile}} {
		dir := t.TempDir()
		if _, _, err := LoadOrCreate(dir, nil, now); err != nil {
			t.Fatal(err)
		}
		if err := os.Remove(filepath.Join(dir, tt.removed)); err != nil {
			t.Fatal(err)
		}
		_, _, err := LoadOrCreate(dir, nil, now)
		left := filepath.Join(dir, tt.left)
		if err == nil || !strings.Contains(err.Error(), "delete "+left+" and start again") || !strings.Contains(err.Error(), "re-pair") {
			t.Fatalf("%s removed: err = %v, want 'delete %s'", tt.removed, err, left)
		}
		// Following the message recovers: delete the named file, start again.
		if err := os.Remove(left); err != nil {
			t.Fatal(err)
		}
		if _, created, err := LoadOrCreate(dir, nil, now); err != nil || !created {
			t.Fatalf("after deleting %s: created=%v err=%v", left, created, err)
		}
	}
}

func TestCorruptPairNamesBothFiles(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := LoadOrCreate(dir, nil, now); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, CertFile), []byte("garbage"), 0o600); err != nil {
		t.Fatal(err)
	}
	_, _, err := LoadOrCreate(dir, nil, now)
	if err == nil || !strings.Contains(err.Error(), filepath.Join(dir, CertFile)) || !strings.Contains(err.Error(), filepath.Join(dir, KeyFile)) {
		t.Fatalf("err = %v", err)
	}
}

func TestLooseKeyRefused(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := LoadOrCreate(dir, nil, now); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(filepath.Join(dir, KeyFile), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := LoadOrCreate(dir, nil, now); err == nil || !strings.Contains(err.Error(), "mode") {
		t.Fatalf("err = %v, want mode refusal", err)
	}
}
