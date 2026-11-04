package version

import "testing"

func TestDefaults(t *testing.T) {
	if Version != "0.0.0-dev" || Commit != "unknown" {
		t.Fatalf("defaults %q %q", Version, Commit)
	}
	if ProtocolVersion < 1 || MinPhoneProtocol < 1 || MinPhoneProtocol > ProtocolVersion {
		t.Fatalf("protocol %d min %d", ProtocolVersion, MinPhoneProtocol)
	}
}

func TestLine(t *testing.T) {
	old := Commit
	defer func() { Commit = old }()
	Commit = "abc1234"
	if got, want := Line("1.2.3"), "aristotle-server 1.2.3 (abc1234) protocol 1"; got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}
