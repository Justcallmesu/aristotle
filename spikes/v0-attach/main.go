// Command v0attach is a throwaway spike: it joins Hermes' live sessions the way Hermes Desktop
// does (control-socket ticket -> /api/ws). See docs/spikes/v0-attach.md.
package main

import (
	"bufio"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"sort"
	"strings"
	"syscall"
	"time"
)

const spikeTitle = "aristotle-spike"

type common struct {
	profile string
	scope   string
	capture string
	echo    bool
}

func (o *common) register(fs *flag.FlagSet) {
	fs.StringVar(&o.profile, "profile", "", "served profile the ticket is bound to (default: gateway home)")
	fs.StringVar(&o.scope, "scope", "host", "ticket scope: host or profile")
	fs.StringVar(&o.capture, "capture", "", "append every frame as a JSON line to this file")
	fs.BoolVar(&o.echo, "echo", true, "print every frame to stdout")
}

func (o *common) dial(label string) (*client, *identity, func(), error) {
	capture, closeCapture, err := openCapture(o.capture)
	if err != nil {
		return nil, nil, nil, err
	}
	writers := []io.Writer{capture}
	if o.echo {
		writers = append(writers, os.Stdout)
	}
	c, id, err := connect(label, o.profile, o.scope, writers...)
	if err != nil {
		closeCapture()
		return nil, id, nil, err
	}
	return c, id, func() { c.close(); closeCapture() }, nil
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: v0attach identify|sessions|create|attach|send|approve|clarify [flags]")
		os.Exit(2)
	}
	commands := map[string]func([]string) error{
		"identify": cmdIdentify, "sessions": cmdSessions, "create": cmdCreate, "attach": cmdAttach,
		"send": cmdSend, "approve": cmdApprove, "clarify": cmdClarify,
	}
	for name, run := range extraCommands {
		commands[name] = run
	}
	run, ok := commands[os.Args[1]]
	if !ok {
		fmt.Fprintf(os.Stderr, "unknown command %q\n", os.Args[1])
		os.Exit(2)
	}
	if err := run(os.Args[2:]); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

// cmdIdentify: identify (control socket) + ticket + WS connect + runtime.describe.
func cmdIdentify(args []string) error {
	fs := flag.NewFlagSet("identify", flag.ExitOnError)
	var o common
	o.register(fs)
	_ = fs.Parse(args)
	id, err := identify()
	if err != nil {
		return err
	}
	summary := map[string]any{"identify_keys": sortedKeys(id.raw), "protocol": id.Protocol, "runtime_protocol": id.RuntimeProtocol,
		"api_origin": id.APIOrigin, "state": id.State, "supervisor": id.Supervisor, "profile": id.Profile,
		"hermes_home": id.HermesHome, "served_profiles": id.ServedProfiles, "capabilities": id.Capabilities,
		"authority_epoch": id.AuthorityEpoch, "code_sha": id.CodeSHA, "kind": id.Kind}
	printJSON(map[string]any{"step": "identify", "result": summary})
	o.echo = false
	c, _, done, err := o.dial("identify")
	if err != nil {
		return fmt.Errorf("ticket/connect: %w", err)
	}
	defer done()
	printJSON(map[string]any{"step": "ws_connect", "result": "gateway.ready received; client.capabilities accepted"})
	describe, err := c.call("runtime.describe", map[string]any{}, 15*time.Second)
	if err != nil {
		return err
	}
	var d map[string]any
	_ = json.Unmarshal(describe, &d)
	delete(d, "skin")
	printJSON(map[string]any{"step": "runtime.describe", "result": d})
	return nil
}

// cmdSessions prints only the count and field names of session.list rows (never titles/content).
func cmdSessions(args []string) error {
	fs := flag.NewFlagSet("sessions", flag.ExitOnError)
	var o common
	o.register(fs)
	listScope := fs.String("list-scope", "live", "session.list scope: live or stored")
	profileParam := fs.String("profile-param", "", "send params.profile=<name> (host-scoped routing)")
	_ = fs.Parse(args)
	o.echo, o.capture = false, ""
	c, _, done, err := o.dial("sessions")
	if err != nil {
		return err
	}
	defer done()
	params := map[string]any{"scope": *listScope, "limit": 200}
	if *profileParam != "" {
		params["profile"] = *profileParam
	}
	raw, err := c.call("session.list", params, 30*time.Second)
	if err != nil {
		printJSON(map[string]any{"step": "session.list", "params": params, "error": err.Error()})
		return nil
	}
	var result struct {
		Sessions []map[string]any `json:"sessions"`
		Scope    string           `json:"scope"`
	}
	_ = json.Unmarshal(raw, &result)
	fields := map[string]bool{}
	sources := map[string]int{}
	running := 0
	for _, row := range result.Sessions {
		for k := range row {
			fields[k] = true
		}
		src, _ := row["source"].(string)
		sources[src]++
		if r, _ := row["running"].(bool); r {
			running++
		}
	}
	printJSON(map[string]any{"step": "session.list", "params": params, "scope": result.Scope, "count": len(result.Sessions),
		"fields": sortedKeys(fields), "by_source": sources, "running": running})
	return nil
}

func cmdCreate(args []string) error {
	fs := flag.NewFlagSet("create", flag.ExitOnError)
	var o common
	o.register(fs)
	title := fs.String("title", spikeTitle, "session title")
	_ = fs.Parse(args)
	if *title != spikeTitle {
		return fmt.Errorf("spike only creates %q", spikeTitle)
	}
	o.echo = false
	c, _, done, err := o.dial("create")
	if err != nil {
		return err
	}
	defer done()
	raw, err := c.call("session.create", map[string]any{"request_id": newID(), "source": "gui", "title": *title}, 60*time.Second)
	if err != nil {
		return err
	}
	var snap map[string]any
	_ = json.Unmarshal(raw, &snap)
	info, _ := snap["info"].(map[string]any)
	printJSON(map[string]any{"step": "session.create", "session_id": snap["session_id"], "snapshot_keys": sortedKeys(snap),
		"info_keys": sortedKeys(info), "title": info["title"], "execution_generation": snap["execution_generation"],
		"revision": snap["revision"], "running": snap["running"]})
	return nil
}

// spikeSessionID resolves the aristotle-spike session by title; every write verb is guarded by it.
func spikeSessionID(c *client) (string, error) {
	raw, err := c.call("session.list", map[string]any{"title": spikeTitle, "limit": 1}, 30*time.Second)
	if err != nil {
		return "", err
	}
	var result struct {
		Sessions []struct {
			SessionID string `json:"session_id"`
			Title     string `json:"title"`
		} `json:"sessions"`
	}
	_ = json.Unmarshal(raw, &result)
	if len(result.Sessions) == 0 || result.Sessions[0].Title != spikeTitle {
		return "", errors.New("no aristotle-spike session")
	}
	return result.Sessions[0].SessionID, nil
}

type session struct {
	c           *client
	id          string
	generation  int            // latest execution_generation seen
	generations map[string]int // prompt_id -> execution_generation
}

// attachSpike guards, then subscribes with session.resume (omit_messages: no transcript).
func attachSpike(c *client, sid string) (*session, error) {
	spike, err := spikeSessionID(c)
	if err != nil {
		return nil, err
	}
	if sid != spike {
		return nil, fmt.Errorf("refusing: %s is not the %s session", sid, spikeTitle)
	}
	raw, err := c.call("session.resume", map[string]any{"session_id": sid, "omit_messages": true}, 60*time.Second)
	if err != nil {
		return nil, err
	}
	s := &session{c: c, id: sid, generations: map[string]int{}}
	var snap struct {
		ExecutionGeneration int `json:"execution_generation"`
		Prompts             []struct {
			PromptID            string `json:"prompt_id"`
			ExecutionGeneration int    `json:"execution_generation"`
		} `json:"prompts"`
	}
	_ = json.Unmarshal(raw, &snap)
	s.generation = snap.ExecutionGeneration
	for _, p := range snap.Prompts {
		s.generations[p.PromptID] = p.ExecutionGeneration
	}
	return s, nil
}

func (s *session) track(f frame) (eventParams, bool) {
	if f.Method != "event" {
		return eventParams{}, false
	}
	var ev eventParams
	_ = json.Unmarshal(f.Params, &ev)
	if ev.SessionID == s.id && ev.ExecutionGeneration != nil && *ev.ExecutionGeneration > s.generation {
		s.generation = *ev.ExecutionGeneration
	}
	var p struct {
		PromptID            string `json:"prompt_id"`
		ExecutionGeneration int    `json:"execution_generation"`
	}
	if json.Unmarshal(ev.Payload, &p) == nil && p.PromptID != "" &&
		(ev.Type == "approval.request" || ev.Type == "clarify.request") {
		s.generations[p.PromptID] = p.ExecutionGeneration
	}
	return ev, true
}

func (s *session) submit(text string) (json.RawMessage, error) {
	return s.c.call("prompt.submit", map[string]any{"session_id": s.id, "text": text, "submission_id": newID()}, 60*time.Second)
}

func (s *session) answer(kind, promptID, value string) (json.RawMessage, error) {
	generation, ok := s.generations[promptID]
	if !ok {
		return nil, fmt.Errorf("unknown prompt %s", promptID)
	}
	method, field := "approval.respond", "choice"
	if kind == "clarify" {
		method, field = "clarify.respond", "answer"
	}
	return s.c.call(method, map[string]any{"session_id": s.id, "execution_generation": generation,
		"prompt_id": promptID, field: value}, 30*time.Second)
}

// cmdAttach streams every frame; optional control FIFO accepts
// "send <text>", "approve <prompt_id> <choice>", "clarify <prompt_id> <answer>", "quit".
func cmdAttach(args []string) error {
	fs := flag.NewFlagSet("attach", flag.ExitOnError)
	var o common
	o.register(fs)
	control := fs.String("control", "", "FIFO path for control commands")
	duration := fs.Duration("duration", 0, "exit after this long (0 = until quit/SIGINT)")
	_ = fs.Parse(args)
	if fs.NArg() != 1 {
		return errors.New("usage: attach [flags] <session_id>")
	}
	label := "attach"
	if o.capture != "" {
		label = strings.TrimSuffix(lastPath(o.capture), ".jsonl")
	}
	c, _, done, err := o.dial(label)
	if err != nil {
		return err
	}
	defer done()
	s, err := attachSpike(c, fs.Arg(0))
	if err != nil {
		return err
	}
	commands := make(chan string, 16)
	if *control != "" {
		go readControl(*control, commands)
	}
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	var deadline <-chan time.Time
	if *duration > 0 {
		deadline = time.After(*duration)
	}
	for {
		select {
		case f, open := <-c.events:
			if !open {
				return fmt.Errorf("socket closed: %v", <-c.done)
			}
			s.track(f)
		case line := <-commands:
			if quit := runControl(s, line); quit {
				return nil
			}
		case <-stop:
			return nil
		case <-deadline:
			return nil
		}
	}
}

func runControl(s *session, line string) bool {
	verb, rest, _ := strings.Cut(strings.TrimSpace(line), " ")
	var err error
	switch verb {
	case "quit":
		return true
	case "send":
		_, err = s.submit(rest)
	case "approve", "clarify":
		promptID, value, _ := strings.Cut(rest, " ")
		kind := map[string]string{"approve": "approval", "clarify": "clarify"}[verb]
		_, err = s.answer(kind, promptID, value)
	case "steer", "redirect":
		_, err = s.c.call("session."+verb, map[string]any{"session_id": s.id, "execution_generation": s.generation, "text": rest}, 30*time.Second)
	case "interrupt":
		_, err = s.c.call("session.interrupt", map[string]any{"session_id": s.id, "execution_generation": s.generation}, 30*time.Second)
	case "raw": // raw <method> <json params>; session_id is forced to the spike session
		method, body, _ := strings.Cut(rest, " ")
		params := map[string]any{}
		if body != "" {
			if err = json.Unmarshal([]byte(body), &params); err != nil {
				break
			}
		}
		if _, ok := params["session_id"]; ok || strings.HasPrefix(method, "prompt.") || strings.HasPrefix(method, "session.") {
			params["session_id"] = s.id
		}
		_, err = s.c.call(method, params, 60*time.Second)
	default:
		err = fmt.Errorf("unknown control %q", verb)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "control error:", err)
	}
	return false
}

func readControl(path string, out chan<- string) {
	f, err := os.OpenFile(path, os.O_RDWR, 0) // RDWR: never sees EOF when writers come and go
	if err != nil {
		fmt.Fprintln(os.Stderr, "control:", err)
		return
	}
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		out <- scanner.Text()
	}
}

func cmdSend(args []string) error {
	fs := flag.NewFlagSet("send", flag.ExitOnError)
	var o common
	o.register(fs)
	wait := fs.Duration("wait", 0, "keep streaming until message.complete or this timeout")
	_ = fs.Parse(args)
	if fs.NArg() != 2 {
		return errors.New("usage: send [flags] <session_id> <text>")
	}
	c, _, done, err := o.dial("send")
	if err != nil {
		return err
	}
	defer done()
	s, err := attachSpike(c, fs.Arg(0))
	if err != nil {
		return err
	}
	if _, err := s.submit(fs.Arg(1)); err != nil {
		return err
	}
	if *wait == 0 {
		return nil
	}
	timeout := time.After(*wait)
	for {
		select {
		case f, open := <-c.events:
			if !open {
				return errors.New("socket closed")
			}
			if ev, ok := s.track(f); ok && ev.Type == "message.complete" && ev.SessionID == s.id {
				return nil
			}
		case <-timeout:
			return nil
		}
	}
}

func cmdApprove(args []string) error { return answerCmd("approve", "approval", args) }
func cmdClarify(args []string) error { return answerCmd("clarify", "clarify", args) }

func answerCmd(name, kind string, args []string) error {
	fs := flag.NewFlagSet(name, flag.ExitOnError)
	var o common
	o.register(fs)
	_ = fs.Parse(args)
	if fs.NArg() != 3 {
		return fmt.Errorf("usage: %s [flags] <session_id> <prompt_id> <value>", name)
	}
	c, _, done, err := o.dial(name)
	if err != nil {
		return err
	}
	defer done()
	s, err := attachSpike(c, fs.Arg(0))
	if err != nil {
		return err
	}
	_, err = s.answer(kind, fs.Arg(1), fs.Arg(2))
	return err
}

func newID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func lastPath(p string) string {
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[i+1:]
	}
	return p
}

func printJSON(v any) {
	raw, _ := json.Marshal(v)
	fmt.Println(string(raw))
}
