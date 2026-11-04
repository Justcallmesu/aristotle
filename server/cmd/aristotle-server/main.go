// Command aristotle-server is the relay between the Aristotle app and Hermes.
package main

import (
	"os"

	"github.com/justcallmesu/aristotle/server/internal/app"
	"github.com/justcallmesu/aristotle/server/internal/version"
)

// The version is set at build time by scripts/build-server.sh (ldflags on package version).
func main() {
	os.Exit(app.Run(os.Args[1:], version.Version, os.Stdout, os.Stderr))
}
