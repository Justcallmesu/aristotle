// Package version holds the build identity and the phone-facing protocol version.
package version

import "fmt"

// Version and Commit are set at build time (scripts/build-server.sh):
// -ldflags "-X <pkg>.Version=1.2.3 -X <pkg>.Commit=abc1234".
var (
	Version = "0.0.0-dev"
	Commit  = "unknown"
)

// ProtocolVersion is the phone-facing API version (docs/protocol.md §11–§14). Bump rules: §0.
const ProtocolVersion = 1

// MinPhoneProtocol is the oldest phone protocol this server still serves.
const MinPhoneProtocol = 1

// Line is the output of `aristotle-server version`.
func Line(version string) string {
	return fmt.Sprintf("aristotle-server %s (%s) protocol %d", version, Commit, ProtocolVersion)
}
