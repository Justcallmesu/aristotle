package hermes

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"
)

// Analysis sessions (docs/protocol.md §20 "Analyzer"; design docs/v2-stocks/runs/TA0/report.md §1-§6, spec P5):
// one hidden, tool-less session in a named profile, on a socket bound to that profile only. The caller
// submits finite prompts and gets the final text of message.complete. Nothing here answers a Hermes
// question: an approval, clarify or srq- request interrupts the turn. Prompt and reply text are never logged.

// Analysis errors (errors.Is).
var (
	// ErrToolsPresent: the new session would load tools (info.tool_count != 0); it was deleted unused.
	ErrToolsPresent = errors.New("hermes analysis session has tools")
	// ErrUnexpectedPrompt: Hermes asked something (approval, clarify, srq- request); the turn was interrupted.
	ErrUnexpectedPrompt = errors.New("hermes asked the analysis turn a question")
	// ErrTurnTimeout: no final answer within the turn timeout; the turn was interrupted.
	ErrTurnTimeout = errors.New("hermes analysis turn timed out")
	// ErrTurnInterrupted: the turn ended interrupted (not by this client).
	ErrTurnInterrupted = errors.New("hermes analysis turn was interrupted")
	// ErrConnLost: the socket to Hermes ended before the answer.
	ErrConnLost = errors.New("hermes connection lost")
	// ErrSessionNotFound: Hermes does not know the session (already deleted).
	ErrSessionNotFound = errors.New("hermes session not found")
)

// TurnError is a turn that ended with status "error" (model or provider failure). Reason is Hermes'
// failure_reason or error text.
type TurnError struct{ Reason string }

func (e *TurnError) Error() string { return "hermes turn failed: " + e.Reason }

// RPCError is a JSON-RPC refusal from Hermes; Reason is error.data.reason (else the message).
type RPCError struct {
	Method     string
	Code       int
	Reason     string
	Revision   *int64
	Generation *int64
}

func (e *RPCError) Error() string {
	return fmt.Sprintf("hermes refused %s: %d %s", e.Method, e.Code, e.Reason)
}

const (
	// DefaultTurnTimeout bounds one analysis turn (spec P5).
	DefaultTurnTimeout = 120 * time.Second
	defaultPingEvery   = 15 * time.Second
	controlCallTimeout = 10 * time.Second
	busyRetries        = 6
	busyRetryDelay     = 500 * time.Millisecond
	frameQueue         = 64
)

// analysisCreateParams are the session.create params of every analysis session (spec P5, TA0 §2-§4):
// internal source "tool" and hidden keep it off every list; ["no_mcp"] means no tools at all (an empty
// list would still load every MCP server); ignore_rules drops SOUL, memory and context files. No title
// (a titled create returns an existing session).
func analysisCreateParams(requestID string) map[string]any {
	return map[string]any{"request_id": requestID, "source": "tool", "hidden": true, "toolsets": []string{"no_mcp"},
		"ignore_rules": true, "reasoning": "low", "max_turns": 3, "run_budget": 90}
}

// AnalysisOptions configure OpenAnalysis.
type AnalysisOptions struct {
	Profile     string        // served profile name, e.g. "aristotle-stocks"
	TurnTimeout time.Duration // default DefaultTurnTimeout
	PingEvery   time.Duration // gateway.ping period while waiting; default 15 s
}

// ServesProfile checks that Hermes runs and serves profile: nil, an Identify error
// (ErrHermesNotRunning, ErrNotReady, ...) or ErrUnknownProfile.
func (c *Connector) ServesProfile(ctx context.Context, profile string) error {
	id, err := c.Identify(ctx)
	if err != nil {
		return err
	}
	_, err = id.resolveProfile(profile)
	return err
}

// AnalysisSession is one open analysis session and its socket. Ask, Delete and Close must not run
// concurrently.
type AnalysisSession struct {
	conn       *Conn
	ctx        context.Context // the socket's lifetime; cancelling it closes the socket
	cancel     context.CancelFunc
	frames     chan []byte
	readDone   chan struct{}
	readOnce   sync.Once
	nextID     int
	timeout    time.Duration
	pingEvery  time.Duration
	sessionID  string
	model      string
	revision   int64
	generation int64

	// Event state, filled while frames are read.
	completed  map[string]json.RawMessage // admission_id -> message.complete payload
	unexpected bool                       // Hermes asked a question
	gap        bool                       // a session.replay_gap: events may be lost
}

// OpenAnalysis dials a profile-scoped socket and creates the session. When the session would load
// any tool it is deleted at once and ErrToolsPresent is returned.
func (c *Connector) OpenAnalysis(ctx context.Context, o AnalysisOptions) (*AnalysisSession, error) {
	conn, err := c.DialProfile(ctx, o.Profile)
	if err != nil {
		return nil, err
	}
	s := newAnalysisSession(conn, o)
	snap, err := s.call(ctx, "session.create", analysisCreateParams(newID()))
	if err != nil {
		s.Close()
		return nil, err
	}
	var res struct {
		SessionID  string `json:"session_id"`
		Revision   int64  `json:"revision"`
		Generation int64  `json:"execution_generation"`
		Info       struct {
			ToolCount *int   `json:"tool_count"`
			Model     string `json:"model"`
		} `json:"info"`
	}
	if err := json.Unmarshal(snap, &res); err != nil || res.SessionID == "" {
		s.Close()
		return nil, fmt.Errorf("hermes session.create: unusable answer")
	}
	s.sessionID, s.revision, s.generation, s.model = res.SessionID, res.Revision, res.Generation, res.Info.Model
	if res.Info.ToolCount == nil || *res.Info.ToolCount != 0 {
		_ = s.Delete(ctx)
		s.Close()
		if res.Info.ToolCount == nil {
			return nil, fmt.Errorf("%w: tool_count missing", ErrToolsPresent)
		}
		return nil, fmt.Errorf("%w: %d", ErrToolsPresent, *res.Info.ToolCount)
	}
	return s, nil
}

func newAnalysisSession(conn *Conn, o AnalysisOptions) *AnalysisSession {
	ctx, cancel := context.WithCancel(context.Background())
	s := &AnalysisSession{conn: conn, ctx: ctx, cancel: cancel, frames: make(chan []byte, frameQueue),
		readDone: make(chan struct{}), nextID: 1, timeout: o.TurnTimeout, pingEvery: o.PingEvery,
		completed: map[string]json.RawMessage{}}
	if s.timeout <= 0 {
		s.timeout = DefaultTurnTimeout
	}
	if s.pingEvery <= 0 {
		s.pingEvery = defaultPingEvery
	}
	go s.readLoop()
	return s
}

// ID is the Hermes session id.
func (s *AnalysisSession) ID() string { return s.sessionID }

// Model is the model the session runs (from the create snapshot).
func (s *AnalysisSession) Model() string { return s.model }

// Close ends the socket; a running turn keeps running in Hermes.
func (s *AnalysisSession) Close() {
	s.readOnce.Do(func() {
		s.cancel() // unblocks the reader; the socket closes with it
		_ = s.conn.Close()
	})
	<-s.readDone
}

func (s *AnalysisSession) readLoop() {
	defer close(s.readDone)
	for {
		frame, err := s.conn.Read(s.ctx)
		if err != nil {
			return
		}
		select {
		case s.frames <- frame:
		case <-s.ctx.Done():
			return
		}
	}
}

// Ask submits one finite prompt and waits for its final text. On a timeout, a question from Hermes or
// ctx ending, the turn is interrupted first.
func (s *AnalysisSession) Ask(ctx context.Context, text string) (string, error) {
	res, err := s.call(ctx, "prompt.submit", map[string]any{
		"session_id": s.sessionID, "text": text, "submission_id": newID(), "finite": true})
	if err != nil {
		return "", err
	}
	var adm struct {
		AdmissionID string `json:"admission_id"`
		Generation  *int64 `json:"execution_generation"`
	}
	if json.Unmarshal(res, &adm) != nil || adm.AdmissionID == "" {
		return "", errors.New("hermes prompt.submit: no admission_id")
	}
	if adm.Generation != nil {
		s.generation = *adm.Generation
	}
	return s.await(ctx, adm.AdmissionID)
}

// await reads events until the admission's message.complete (or its receipt) settles the turn.
func (s *AnalysisSession) await(ctx context.Context, admission string) (string, error) {
	deadline := time.NewTimer(s.timeout)
	defer deadline.Stop()
	ping := time.NewTicker(s.pingEvery)
	defer ping.Stop()
	for {
		if text, done, err := s.settled(ctx, admission); done {
			return text, err
		}
		select {
		case frame := <-s.frames:
			s.handle(frame)
		case <-s.readDone:
			s.drain()
			if text, done, err := s.settled(ctx, admission); done {
				return text, err
			}
			return "", ErrConnLost
		case <-ping.C:
			s.ping()
		case <-deadline.C:
			if text, done, err := s.fromReceipt(ctx, admission); done {
				return text, err
			}
			s.interrupt()
			return "", ErrTurnTimeout
		case <-ctx.Done():
			s.interrupt()
			return "", ctx.Err()
		}
	}
}

// settled reports whether the turn is over from the event state seen so far.
func (s *AnalysisSession) settled(ctx context.Context, admission string) (string, bool, error) {
	if payload, ok := s.completed[admission]; ok {
		text, err := completeOutcome(payload)
		return text, true, err
	}
	if s.unexpected {
		s.interrupt()
		return "", true, ErrUnexpectedPrompt
	}
	if s.gap {
		s.gap = false
		return s.fromReceipt(ctx, admission)
	}
	return "", false, nil
}

// completeOutcome reads a message.complete payload (tui_gateway/contracts/events.py MessageCompletePayload).
func completeOutcome(payload json.RawMessage) (string, error) {
	var p struct {
		Text          json.RawMessage `json:"text"`
		Status        string          `json:"status"`
		Outcome       string          `json:"outcome"`
		FailureReason string          `json:"failure_reason"`
		Error         string          `json:"error"`
	}
	_ = json.Unmarshal(payload, &p)
	switch {
	case p.Status == "interrupted" || p.Outcome == "cancelled":
		return "", ErrTurnInterrupted
	case p.Status == "error":
		return "", &TurnError{Reason: firstNonEmpty(p.FailureReason, p.Error, "error")}
	}
	var text string
	if json.Unmarshal(p.Text, &text) != nil {
		text = string(p.Text) // a structured JsonValue: hand the raw JSON on
	}
	return text, nil
}

// fromReceipt is the fallback when events may be lost: prompt.receipt with include_result.
func (s *AnalysisSession) fromReceipt(ctx context.Context, admission string) (string, bool, error) {
	cctx, cancel := context.WithTimeout(ctx, controlCallTimeout)
	defer cancel()
	raw, err := s.call(cctx, "prompt.receipt", map[string]any{
		"session_id": s.sessionID, "admission_id": admission, "include_result": true})
	if err != nil {
		return "", false, nil
	}
	var r struct {
		Status  string `json:"status"`
		Outcome string `json:"outcome"`
		Result  struct {
			FinalResponse string `json:"final_response"`
			Error         string `json:"error"`
		} `json:"result"`
	}
	if json.Unmarshal(raw, &r) != nil || r.Status != "terminal" {
		return "", false, nil
	}
	switch r.Outcome {
	case "completed":
		return r.Result.FinalResponse, true, nil
	case "interrupted", "cancelled":
		return "", true, ErrTurnInterrupted
	default:
		return "", true, &TurnError{Reason: firstNonEmpty(r.Result.Error, r.Outcome)}
	}
}

// interrupt stops the running turn (best effort, bounded); a stale generation is retried once at the
// generation Hermes names.
func (s *AnalysisSession) interrupt() {
	ctx, cancel := context.WithTimeout(context.Background(), controlCallTimeout)
	defer cancel()
	_, err := s.call(ctx, "session.interrupt", map[string]any{"session_id": s.sessionID, "execution_generation": s.generation})
	var re *RPCError
	if errors.As(err, &re) && re.Reason == "stale_generation" && re.Generation != nil {
		s.generation = *re.Generation
		_, _ = s.call(ctx, "session.interrupt", map[string]any{"session_id": s.sessionID, "execution_generation": s.generation})
	}
}

// Delete removes the session (session.mutate delete). The session must be idle: session_busy is retried
// briefly, revision_conflict once per conflict at the revision Hermes names.
func (s *AnalysisSession) Delete(ctx context.Context) error {
	return deleteSession(ctx, s, s.sessionID, s.revision)
}

func deleteSession(ctx context.Context, s *AnalysisSession, sessionID string, revision int64) error {
	conflicts := 0
	for busy := 0; ; {
		_, err := s.call(ctx, "session.mutate", map[string]any{"session_id": sessionID, "request_id": newID(),
			"expected_revision": revision, "operation": "delete", "payload": map[string]any{}})
		var re *RPCError
		if !errors.As(err, &re) {
			return err
		}
		switch {
		case re.Reason == "revision_conflict" && re.Revision != nil && conflicts < 2:
			conflicts++
			revision = *re.Revision
		case re.Reason == "session_busy" && busy < busyRetries:
			busy++
			select {
			case <-time.After(busyRetryDelay):
			case <-ctx.Done():
				return ctx.Err()
			}
		case re.Reason == "not_found":
			return fmt.Errorf("%w: %v", ErrSessionNotFound, err)
		default:
			return err
		}
	}
}

// DeleteAnalysisSession deletes a session left from an earlier run (the server's sweep of failed jobs) on
// a fresh profile-scoped socket. Its revision is unknown, so the first try sends 0 and a conflict is
// retried at the revision Hermes names. ErrSessionNotFound when it is already gone.
func (c *Connector) DeleteAnalysisSession(ctx context.Context, profile, sessionID string) error {
	conn, err := c.DialProfile(ctx, profile)
	if err != nil {
		return err
	}
	s := newAnalysisSession(conn, AnalysisOptions{Profile: profile})
	defer s.Close()
	return deleteSession(ctx, s, sessionID, 0)
}

// call sends one JSON-RPC request and waits for its answer, handling every other frame on the way.
func (s *AnalysisSession) call(ctx context.Context, method string, params map[string]any) (json.RawMessage, error) {
	s.nextID++
	id := s.nextID
	frame, err := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": id, "method": method, "params": params})
	if err != nil {
		return nil, err
	}
	if err := s.conn.Write(s.ctx, frame); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrConnLost, err)
	}
	for {
		select {
		case raw := <-s.frames:
			if res, mine, err := s.response(raw, id, method); mine {
				return res, err
			}
		case <-s.readDone:
			for {
				select {
				case raw := <-s.frames:
					if res, mine, err := s.response(raw, id, method); mine {
						return res, err
					}
				default:
					return nil, ErrConnLost
				}
			}
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
}

// drain handles frames the reader queued before it stopped.
func (s *AnalysisSession) drain() {
	for {
		select {
		case raw := <-s.frames:
			s.handle(raw)
		default:
			return
		}
	}
}

// response returns the answer when raw answers request id; any other frame is handled as an event.
func (s *AnalysisSession) response(raw []byte, id int, method string) (json.RawMessage, bool, error) {
	var head struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Result json.RawMessage `json:"result"`
		Error  *struct {
			Code    int    `json:"code"`
			Message string `json:"message"`
			Data    struct {
				Reason     string `json:"reason"`
				Revision   *int64 `json:"revision"`
				Generation *int64 `json:"execution_generation"`
			} `json:"data"`
		} `json:"error"`
	}
	if json.Unmarshal(raw, &head) != nil || head.Method != "" || string(head.ID) != fmt.Sprint(id) {
		s.handle(raw)
		return nil, false, nil
	}
	if head.Error != nil {
		return nil, true, &RPCError{Method: method, Code: head.Error.Code, Reason: firstNonEmpty(head.Error.Data.Reason, head.Error.Message),
			Revision: head.Error.Data.Revision, Generation: head.Error.Data.Generation}
	}
	return head.Result, true, nil
}

// handle records what an incoming frame means for the session: a server request or an approval/clarify
// event marks the turn as asked; message.complete is kept by admission; session.info and the envelope
// update the revision and generation; session.replay_gap marks events as possibly lost.
func (s *AnalysisSession) handle(raw []byte) {
	var f struct {
		ID     json.RawMessage `json:"id"`
		Method string          `json:"method"`
		Params struct {
			Type        string          `json:"type"`
			SessionID   string          `json:"session_id"`
			AdmissionID string          `json:"admission_id"`
			Generation  *int64          `json:"execution_generation"`
			Payload     json.RawMessage `json:"payload"`
		} `json:"params"`
	}
	if json.Unmarshal(raw, &f) != nil {
		return
	}
	if f.Method != "" && f.Method != "event" && len(f.ID) > 0 && string(f.ID) != "null" {
		s.unexpected = true // srq- server request: never answered (docs/protocol.md §6)
		return
	}
	if f.Method != "event" || (f.Params.SessionID != "" && f.Params.SessionID != s.sessionID) {
		return
	}
	if f.Params.Generation != nil {
		s.generation = *f.Params.Generation
	}
	var p struct {
		AdmissionID string `json:"admission_id"`
		Revision    *int64 `json:"revision"`
		Generation  *int64 `json:"execution_generation"`
	}
	_ = json.Unmarshal(f.Params.Payload, &p)
	switch f.Params.Type {
	case "approval.request", "clarify.request":
		s.unexpected = true
	case "message.complete":
		if adm := firstNonEmpty(f.Params.AdmissionID, p.AdmissionID); adm != "" {
			s.completed[adm] = f.Params.Payload
		}
	case "session.info":
		if p.Revision != nil {
			s.revision = *p.Revision
		}
		if p.Generation != nil {
			s.generation = *p.Generation
		}
	case "session.updated":
		if p.Revision != nil {
			s.revision = *p.Revision
		}
	case "session.replay_gap":
		s.gap = true
	}
}

func (s *AnalysisSession) ping() {
	s.nextID++
	frame := fmt.Sprintf(`{"jsonrpc":"2.0","id":%d,"method":"gateway.ping","params":{}}`, s.nextID)
	_ = s.conn.Write(s.ctx, []byte(frame))
}

func newID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
