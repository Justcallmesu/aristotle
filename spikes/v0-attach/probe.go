package main

// probe: negative checks a v1 relay must handle (ticket reuse, Origin header, expiry).

import (
	"flag"
	"fmt"
	"time"
)

func init() { extraCommands["probe"] = cmdProbe }

var extraCommands = map[string]func([]string) error{}

func cmdProbe(args []string) error {
	fs := flag.NewFlagSet("probe", flag.ExitOnError)
	expiry := fs.Bool("expiry", false, "also wait 31 s and try an expired ticket")
	_ = fs.Parse(args)
	id, err := identify()
	if err != nil {
		return err
	}
	profile, err := id.profileFor("")
	if err != nil {
		return err
	}
	ticketAnswerShape(id, profile.ProfileID)
	ticket, err := mintTicket(id, profile.ProfileID, "host")
	if err != nil {
		return err
	}
	first := tryDial(id.APIOrigin, ticket, "")
	printJSON(map[string]any{"probe": "fresh ticket", "outcome": first})
	printJSON(map[string]any{"probe": "same ticket again (single use)", "outcome": tryDial(id.APIOrigin, ticket, "")})
	ticket2, err := mintTicket(id, profile.ProfileID, "host")
	if err != nil {
		return err
	}
	printJSON(map[string]any{"probe": "fresh ticket + Origin header", "outcome": tryDial(id.APIOrigin, ticket2, "http://127.0.0.1")})
	printJSON(map[string]any{"probe": "garbage ticket", "outcome": tryDial(id.APIOrigin, "not-a-ticket", "")})
	_, err = mintTicket(id, profile.ProfileID+"-nope", "host")
	printJSON(map[string]any{"probe": "ticket for unserved profile_id", "outcome": errString(err)})
	_, err = controlQuery(map[string]any{"protocol": 1, "id": 1, "verb": "session-ticket", "params": map[string]any{
		"profile_id": profile.ProfileID, "instance_id": "stale", "purpose": "interactive", "scope": "host"}})
	printJSON(map[string]any{"probe": "ticket with stale instance_id", "outcome": errString(err)})
	if *expiry {
		ticket3, err := mintTicket(id, profile.ProfileID, "host")
		if err != nil {
			return err
		}
		time.Sleep(31 * time.Second)
		printJSON(map[string]any{"probe": "ticket used after 31 s", "outcome": tryDial(id.APIOrigin, ticket3, "")})
	}
	return nil
}

// ticketAnswerShape prints the session-ticket answer with the ticket value redacted.
func ticketAnswerShape(id *identity, profileID string) {
	envelope, err := controlQuery(map[string]any{"protocol": 1, "id": 1, "verb": "session-ticket", "params": map[string]any{
		"profile_id": profileID, "instance_id": id.InstanceID, "purpose": "interactive", "scope": "host"}})
	if err != nil {
		printJSON(map[string]any{"probe": "ticket answer shape", "outcome": err.Error()})
		return
	}
	if result, ok := envelope["result"].(map[string]any); ok {
		result["ticket"] = fmt.Sprintf("<redacted %d chars>", len(fmt.Sprint(result["ticket"])))
	}
	printJSON(map[string]any{"probe": "ticket answer shape", "envelope": envelope})
}

// tryDial reports what the upgrade does and, when accepted, the first frame's type.
func tryDial(apiOrigin, ticket, origin string) string {
	ws, resp, err := dialGatewayWithOrigin(apiOrigin, ticket, origin)
	if err != nil {
		status := ""
		if resp != nil {
			status = fmt.Sprintf(" (HTTP %d)", resp.StatusCode)
		}
		return "upgrade failed: " + err.Error() + status
	}
	defer ws.Close()
	_ = ws.conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	msg, err := ws.ReadMessage()
	if err != nil {
		return "upgraded, then: " + err.Error()
	}
	if len(msg) > 80 {
		msg = msg[:80]
	}
	return "upgraded, first frame: " + string(msg) + "…"
}

func errString(err error) string {
	if err == nil {
		return "accepted"
	}
	return err.Error()
}

func errorf(format string, args ...any) error { return fmt.Errorf(format, args...) }
