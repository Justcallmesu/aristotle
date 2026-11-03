package main

// rest: one read-only REST call with a native-http ticket (X-Hermes-Gateway-Ticket header,
// hermes_cli/dashboard_auth/native_http.py:15, :287-312). Prints status and shape only.

import (
	"encoding/json"
	"flag"
	"io"
	"net/http"
	"sort"
	"strings"
	"time"
)

func init() { extraCommands["rest"] = cmdRest }

func cmdRest(args []string) error {
	fs := flag.NewFlagSet("rest", flag.ExitOnError)
	profileName := fs.String("profile", "", "profile the native-http ticket is minted for")
	noTicket := fs.Bool("no-ticket", false, "send the request without a ticket")
	_ = fs.Parse(args)
	if fs.NArg() != 1 || !strings.HasPrefix(fs.Arg(0), "/api/") {
		return errorf("usage: rest [flags] /api/<path>")
	}
	id, err := identify()
	if err != nil {
		return err
	}
	profile, err := id.profileFor(*profileName)
	if err != nil {
		return err
	}
	req, _ := http.NewRequest(http.MethodGet, id.APIOrigin+fs.Arg(0), nil)
	if !*noTicket {
		ticket, err := mintPurposeTicket(id, profile.ProfileID, "native-http")
		if err != nil {
			return err
		}
		req.Header.Set("X-Hermes-Gateway-Ticket", ticket)
	}
	resp, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	printJSON(map[string]any{"path": fs.Arg(0), "ticket": !*noTicket, "status": resp.StatusCode, "shape": shapeOf(body)})
	return nil
}

// shapeOf describes JSON without values: keys, list lengths, first-row keys.
func shapeOf(body []byte) any {
	var v any
	if json.Unmarshal(body, &v) != nil {
		return map[string]any{"non_json_bytes": len(body)}
	}
	return describe(v, 0)
}

func describe(v any, depth int) any {
	switch t := v.(type) {
	case map[string]any:
		if depth >= 2 {
			return "object"
		}
		out := map[string]any{}
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			out[k] = describe(t[k], depth+1)
		}
		return out
	case []any:
		row := any(nil)
		if len(t) > 0 {
			row = describe(t[0], depth+1)
		}
		return map[string]any{"len": len(t), "row": row}
	case string:
		if t == "Unauthorized" || t == "profile_scope_mismatch" || strings.HasPrefix(t, "Not") {
			return t // error details only
		}
		return "string"
	case float64:
		return "number"
	case bool:
		return "bool"
	default:
		return "null"
	}
}
