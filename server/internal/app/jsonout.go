package app

import (
	"encoding/json"
	"io"
	"slices"
)

// The desktop app runs `aristotle-server <command> --json` and parses stdout (docs/protocol.md, "CLI JSON
// output (desktop app)"). Every --json command prints through printJSON or failCmd, so the two shapes
// (the result, or {"error":"..."}) have one owner.

// jsonFlag switches a command to JSON output.
const jsonFlag = "--json"

// stripJSON removes every --json from args and reports whether there was one.
func stripJSON(args []string) ([]string, bool) {
	rest := slices.DeleteFunc(slices.Clone(args), func(a string) bool { return a == jsonFlag })
	return rest, len(rest) != len(args)
}

// printJSON writes v as one JSON object and a newline to w.
func printJSON(w io.Writer, v any) {
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

// jsonError is the failure shape.
type jsonError struct {
	Error string `json:"error"`
}

// failCmd reports a failed command: `error: <text>` on stderr, or {"error":"<text>"} on stdout under
// --json. It returns the exit code (1 either way).
func failCmd(stdout, stderr io.Writer, asJSON bool, err error) int {
	if asJSON {
		printJSON(stdout, jsonError{Error: err.Error()})
		return 1
	}
	io.WriteString(stderr, "error: "+err.Error()+"\n")
	return 1
}

// emitJSON prints a successful --json result and returns exit code 0.
func emitJSON(stdout io.Writer, v any) int {
	printJSON(stdout, v)
	return 0
}
