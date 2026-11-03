// Command aristotle-server is the relay between the Aristotle app and Hermes.
package main

import (
	"os"

	"github.com/justcallmesu/aristotle/server/internal/app"
)

// version is set at build time: -ldflags "-X main.version=1.0.0".
var version = "dev"

func main() {
	os.Exit(app.Run(os.Args[1:], version, os.Stdout, os.Stderr))
}
