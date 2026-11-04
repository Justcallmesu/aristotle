package terminal

import (
	"errors"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// Close codes on the terminal socket (docs/protocol.md §14).
const (
	CodeExit        websocket.StatusCode = 1000 // the shell exited (after an exit message)
	CodeGoingAway   websocket.StatusCode = 1001 // aristotle-server is stopping
	CodeBadControl  websocket.StatusCode = 1008 // a text frame that is not a JSON control object
	CodeTooBig      websocket.StatusCode = 1009 // a frame over MaxFrameBytes (sent by the WebSocket library)
	CodeInternal    websocket.StatusCode = 1011 // pong_timeout, or the shell could not start
	CodeRevoked     websocket.StatusCode = 4401 // the device was revoked
	CodeForbidden   websocket.StatusCode = 4403 // terminal disabled, or this device's grant removed
	CodeTimeLimit   websocket.StatusCode = 4408 // idle (no input) or the maximum session length
	CodeTooMany     websocket.StatusCode = 4430 // per-device or total terminal cap
	codeAbnormal    websocket.StatusCode = 1006 // never sent: the phone vanished without a close frame
	reasonExit                           = "exit"
	reasonIdle                           = "idle_timeout"
	reasonMaxLength                      = "max_session"
)

type closeReason struct {
	code   websocket.StatusCode
	reason string
}

var (
	closeGoingAway  = closeReason{CodeGoingAway, "going_away"}
	closeRevoked    = closeReason{CodeRevoked, "revoked"}
	closeTooMany    = closeReason{CodeTooMany, "too_many_terminals"}
	closeBadControl = closeReason{CodeBadControl, "bad_control_frame"}
	closePongLost   = closeReason{CodeInternal, "pong_timeout"}
	closeNoShell    = closeReason{CodeInternal, "shell_start_failed"}
	closeExit       = closeReason{CodeExit, reasonExit}
)

// phoneClose records how the phone side ended; the code is for the log (the phone already knows).
func phoneClose(err error) closeReason {
	if code := websocket.CloseStatus(err); code != -1 {
		return closeReason{code, ""}
	}
	if errors.Is(err, websocket.ErrMessageTooBig) {
		return closeReason{CodeTooBig, "message_too_big"}
	}
	return closeReason{codeAbnormal, ""}
}

// sendable reports whether code may appear in a close frame (RFC 6455 §7.4).
func sendable(code websocket.StatusCode) bool {
	switch code {
	case 1004, 1005, 1006, 1015:
		return false
	}
	return (code >= 1000 && code <= 1014) || (code >= 3000 && code <= 4999)
}

// Terminal size bounds for resize (the PTY's winsize fields are uint16; T19 red-team M3).
const (
	minCols, maxCols = 20, 500
	minRows, maxRows = 5, 200
	defaultCols      = 80
	defaultRows      = 24
)

// clampSize keeps a requested size inside the bounds.
func clampSize(cols, rows int) (int, int) {
	return min(max(cols, minCols), maxCols), min(max(rows, minRows), maxRows)
}

// byteRate is a token bucket over input bytes: burst bytes at once, refilled at rate bytes per second.
// Typing never comes near it; a paste larger than the burst is slowed down, never refused.
type byteRate struct {
	rate, burst, tokens float64
	last                time.Time
}

func newByteRate(perSecond, burst int) *byteRate {
	return &byteRate{rate: float64(perSecond), burst: float64(burst), tokens: float64(burst)}
}

// take spends n bytes at now and returns how long the caller must wait before passing them on.
func (b *byteRate) take(now time.Time, n int) time.Duration {
	if !b.last.IsZero() {
		b.tokens = min(b.burst, b.tokens+now.Sub(b.last).Seconds()*b.rate)
	}
	b.last = now
	b.tokens -= float64(n)
	if b.tokens >= 0 {
		return 0
	}
	return time.Duration(-b.tokens / b.rate * float64(time.Second))
}

// InputQueueBytes bounds the input that waits for the PTY (the program is not reading it) while the
// phone is still being read, so that its pongs keep counting (T19a review m5).
const InputQueueBytes = 4 * MaxFrameBytes

// inputQueue holds the input frames between the phone reader and the PTY writer, at most max bytes
// (counting the frame being written; one frame always fits in an empty queue).
type inputQueue struct {
	max   int
	ready chan struct{} // a frame was added
	space chan struct{} // a frame was written

	mu     sync.Mutex
	frames [][]byte
	size   int
}

func newInputQueue(max int) *inputQueue {
	return &inputQueue{max: max, ready: make(chan struct{}, 1), space: make(chan struct{}, 1)}
}

// tryPush queues msg when it fits and reports whether it did.
func (q *inputQueue) tryPush(msg []byte) bool {
	q.mu.Lock()
	if q.size > 0 && q.size+len(msg) > q.max {
		q.mu.Unlock()
		return false
	}
	q.frames = append(q.frames, msg)
	q.size += len(msg)
	q.mu.Unlock()
	signal(q.ready)
	return true
}

// push queues msg, waiting for room; false when done ended first.
func (q *inputQueue) push(msg []byte, done <-chan struct{}) bool {
	for !q.tryPush(msg) {
		select {
		case <-q.space:
		case <-done:
			return false
		}
	}
	return true
}

// next returns the oldest frame, waiting for one; nil when done ended first. The frame keeps its room
// until written is called.
func (q *inputQueue) next(done <-chan struct{}) []byte {
	for {
		q.mu.Lock()
		if len(q.frames) > 0 {
			msg := q.frames[0]
			q.frames[0] = nil
			q.frames = q.frames[1:]
			q.mu.Unlock()
			return msg
		}
		q.mu.Unlock()
		select {
		case <-q.ready:
		case <-done:
			return nil
		}
	}
}

// written frees the room of a frame returned by next.
func (q *inputQueue) written(n int) {
	q.mu.Lock()
	q.size -= n
	q.mu.Unlock()
	signal(q.space)
}

func signal(c chan struct{}) {
	select {
	case c <- struct{}{}:
	default:
	}
}
