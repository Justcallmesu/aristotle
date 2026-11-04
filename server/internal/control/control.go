// Package control is the local command channel between the aristotle-server
// CLI and the running server: a unix socket at <state>/control.sock, mode
// 0600, one JSON request and one JSON response per connection. Only peers
// with the server's own uid (SO_PEERCRED) are served.
package control

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const (
	SocketName = "control.sock"
	LockName   = "control.lock"
	ioTimeout  = 5 * time.Second
	maxRequest = 64 << 10
)

// ErrForbidden is the error a refused peer receives.
var ErrForbidden = errors.New("forbidden: peer uid does not match the server")

// Request is one command.
type Request struct {
	Cmd  string          `json:"cmd"`
	Args json.RawMessage `json:"args,omitempty"`
}

// Response is the reply to one command.
type Response struct {
	OK     bool            `json:"ok"`
	Error  string          `json:"error,omitempty"`
	Result json.RawMessage `json:"result,omitempty"`
}

// Handler runs one command. Its error text is sent to the CLI.
type Handler func(args json.RawMessage) (any, error)

// Server serves commands on a unix socket.
type Server struct {
	Path     string
	Handlers map[string]Handler
	Log      *slog.Logger
	// PeerUID returns the connecting process's uid; default SO_PEERCRED.
	PeerUID func(*net.UnixConn) (int, error)
	// PeerPID returns the connecting process's pid; default SO_PEERCRED.
	PeerPID func(*net.UnixConn) (int, error)
	// Authorize, when set, runs before every known command with the peer's pid (0 when it could not be
	// read); an error refuses the command with that text. The server uses it to refuse pair, terminal
	// enable and grant-terminal to processes inside a remote terminal (T19 red-team B2).
	Authorize func(cmd string, pid int) error

	ln   *net.UnixListener
	lock io.Closer
	wg   sync.WaitGroup
}

// errLockHeld means another server holds the control lock.
var errLockHeld = errors.New("held by another process")

// Lock takes the exclusive lock on control.lock next to the socket (a no-op
// when already held). The lock is held until Serve returns or Unlock is
// called, so two servers starting at once cannot both judge the socket stale
// and remove each other's, nor both write the state dir's first-run files.
func (s *Server) Lock() error {
	if s.lock != nil {
		return nil
	}
	lockPath := filepath.Join(filepath.Dir(s.Path), LockName)
	lock, err := lockFile(lockPath)
	if errors.Is(err, errLockHeld) {
		return fmt.Errorf("control socket %s is in use (lock %s held): is aristotle-server already running?", s.Path, lockPath)
	}
	if err != nil {
		return err
	}
	s.lock = lock
	return nil
}

// Unlock releases the lock; safe to call when it is not held.
func (s *Server) Unlock() {
	if s.lock != nil {
		s.lock.Close()
		s.lock = nil
	}
}

// Listen creates the socket (0600) under the lock (taken here unless Lock
// already took it). Under the lock, a stale socket file left by a crashed
// server is replaced; a live one (a server that answers) is an error, and so
// is any non-socket file at the path. On error the lock is released.
func (s *Server) Listen() error {
	if err := s.Lock(); err != nil {
		return err
	}
	if err := s.listenLocked(); err != nil {
		s.Unlock()
		return err
	}
	return nil
}

func (s *Server) listenLocked() error {
	if err := removeStale(s.Path); err != nil {
		return err
	}
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: s.Path, Net: "unix"})
	if err != nil {
		return fmt.Errorf("control socket: %w", err)
	}
	// The state dir is 0700, so no other user can reach the socket in the
	// moment before this chmod; SO_PEERCRED is checked on every connection too.
	if err := os.Chmod(s.Path, 0o600); err != nil {
		ln.Close()
		return fmt.Errorf("control socket: %w", err)
	}
	ln.SetUnlinkOnClose(true)
	s.ln = ln
	return nil
}

func removeStale(path string) error {
	info, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	if info.Mode().Type() != os.ModeSocket {
		return fmt.Errorf("control socket %s: path exists and is not a socket: refused", path)
	}
	if c, err := net.DialTimeout("unix", path, time.Second); err == nil {
		c.Close()
		return fmt.Errorf("control socket %s is in use: is aristotle-server already running?", path)
	}
	return os.Remove(path)
}

// Serve accepts connections until ctx is done, then closes and removes the
// socket, waits for in-flight commands and releases the lock.
func (s *Server) Serve(ctx context.Context) {
	go func() {
		<-ctx.Done()
		s.ln.Close()
	}()
	for {
		conn, err := s.ln.AcceptUnix()
		if err != nil {
			break
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.handle(conn)
		}()
	}
	s.wg.Wait()
	s.Unlock()
}

func (s *Server) handle(conn *net.UnixConn) {
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(ioTimeout))
	if err := s.checkPeer(conn); err != nil {
		s.Log.Warn("control connection refused", "err", err)
		writeResponse(conn, Response{Error: ErrForbidden.Error()})
		return
	}
	var req Request
	if err := json.NewDecoder(io.LimitReader(conn, maxRequest)).Decode(&req); err != nil {
		writeResponse(conn, Response{Error: "bad request"})
		return
	}
	writeResponse(conn, s.dispatch(req, s.peerPID(conn)))
}

// peerPID is the connecting process's pid, or 0 when it cannot be read.
func (s *Server) peerPID(conn *net.UnixConn) int {
	peerPID := s.PeerPID
	if peerPID == nil {
		peerPID = PeerPID
	}
	pid, err := peerPID(conn)
	if err != nil {
		return 0
	}
	return pid
}

func (s *Server) checkPeer(conn *net.UnixConn) error {
	peerUID := s.PeerUID
	if peerUID == nil {
		peerUID = PeerUID
	}
	uid, err := peerUID(conn)
	if err != nil {
		return fmt.Errorf("read peer credentials: %w", err)
	}
	if uid != os.Getuid() {
		return fmt.Errorf("peer uid %d is not %d", uid, os.Getuid())
	}
	return nil
}

func (s *Server) dispatch(req Request, pid int) Response {
	h, ok := s.Handlers[req.Cmd]
	if !ok {
		return Response{Error: fmt.Sprintf("unknown command %q", req.Cmd)}
	}
	if s.Authorize != nil {
		if err := s.Authorize(req.Cmd, pid); err != nil {
			return Response{Error: err.Error()}
		}
	}
	s.Log.Info("control command", "cmd", req.Cmd) // never args: they may name a device, results may hold a code
	result, err := h(req.Args)
	if err != nil {
		return Response{Error: err.Error()}
	}
	raw, err := json.Marshal(result)
	if err != nil {
		return Response{Error: "encode result: " + err.Error()}
	}
	return Response{OK: true, Result: raw}
}

func writeResponse(w io.Writer, resp Response) {
	_ = json.NewEncoder(w).Encode(resp)
}

// ErrNotRunning means no server is listening on the control socket.
var ErrNotRunning = errors.New("aristotle-server is not running")

// Call sends one command to the server at socket path and decodes the
// result into out (may be nil).
func Call(path, cmd string, args, out any) error {
	conn, err := net.DialTimeout("unix", path, ioTimeout)
	if err != nil {
		return fmt.Errorf("%w (no control socket at %s): %v", ErrNotRunning, path, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(ioTimeout))
	req := Request{Cmd: cmd}
	if args != nil {
		if req.Args, err = json.Marshal(args); err != nil {
			return err
		}
	}
	var resp Response
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		// A refused peer gets its reply before the server has read the
		// request, then the server closes: the write can fail with EPIPE
		// while the reply is already waiting to be read.
		if json.NewDecoder(conn).Decode(&resp) != nil {
			return fmt.Errorf("send %s: %w", cmd, err)
		}
	} else if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		return fmt.Errorf("read %s reply: %w", cmd, err)
	}
	if !resp.OK {
		return errors.New(resp.Error)
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(resp.Result, out)
}

// SocketPath is the control socket inside the state dir.
func SocketPath(stateDir string) string { return filepath.Join(stateDir, SocketName) }
