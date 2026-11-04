package control

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func shortTempDir(t *testing.T) string {
	t.Helper()
	dir, err := os.MkdirTemp("", "ctl") // t.TempDir names can exceed the 108-byte socket path limit
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.RemoveAll(dir) })
	return dir
}

type echoArgs struct {
	Name string `json:"name"`
}

func startServer(t *testing.T, peerUID func(*net.UnixConn) (int, error), calls *atomic.Int32) (string, context.CancelFunc, chan struct{}) {
	t.Helper()
	path := SocketPath(shortTempDir(t))
	s := &Server{
		Path: path,
		Log:  slog.New(slog.NewTextHandler(io.Discard, nil)),
		Handlers: map[string]Handler{
			"echo": func(raw json.RawMessage) (any, error) {
				calls.Add(1)
				var a echoArgs
				if err := json.Unmarshal(raw, &a); err != nil {
					return nil, err
				}
				return a, nil
			},
		},
		PeerUID: peerUID,
	}
	if err := s.Listen(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { s.Serve(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done })
	return path, cancel, done
}

func TestCallRoundTripAndSocketMode(t *testing.T) {
	var calls atomic.Int32
	path, cancel, done := startServer(t, nil, &calls) // real SO_PEERCRED
	info, err := os.Lstat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 || info.Mode().Type() != os.ModeSocket {
		t.Fatalf("socket mode = %v", info.Mode())
	}
	var out echoArgs
	if err := Call(path, "echo", echoArgs{Name: "poco"}, &out); err != nil {
		t.Fatal(err)
	}
	if out.Name != "poco" {
		t.Fatalf("out = %+v", out)
	}
	if err := Call(path, "nope", nil, nil); err == nil || !strings.Contains(err.Error(), "unknown command") {
		t.Fatalf("err = %v", err)
	}
	cancel()
	<-done
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatalf("socket not removed on shutdown: %v", err)
	}
	if err := Call(path, "echo", nil, nil); err == nil || !strings.Contains(err.Error(), "not running") {
		t.Fatalf("err after stop = %v", err)
	}
}

func TestOtherUIDRefused(t *testing.T) {
	var calls atomic.Int32
	otherUID := func(*net.UnixConn) (int, error) { return os.Getuid() + 1, nil }
	path, _, _ := startServer(t, otherUID, &calls)
	err := Call(path, "echo", echoArgs{Name: "x"}, nil)
	if err == nil || !strings.Contains(err.Error(), "forbidden") {
		t.Fatalf("err = %v, want forbidden", err)
	}
	if calls.Load() != 0 {
		t.Fatal("handler ran for a refused peer")
	}
}

func TestRealPeerUIDIsOwnUID(t *testing.T) {
	path := filepath.Join(shortTempDir(t), "s")
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, err := net.Dial("unix", path)
		if err == nil {
			time.Sleep(100 * time.Millisecond)
			c.Close()
		}
	}()
	conn, err := ln.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	uid, err := PeerUID(conn)
	if err != nil || uid != os.Getuid() {
		t.Fatalf("uid = %d, %v", uid, err)
	}
}

func TestListenStaleLiveAndNonSocket(t *testing.T) {
	var calls atomic.Int32
	live, _, _ := startServer(t, nil, &calls)
	s := &Server{Path: live, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := s.Listen(); err == nil || !strings.Contains(err.Error(), "in use") {
		t.Fatalf("second server on a live socket: err = %v", err)
	}

	dir := shortTempDir(t)
	stale := filepath.Join(dir, SocketName)
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: stale, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	ln.SetUnlinkOnClose(false)
	ln.Close() // leaves a socket file nobody listens on
	s = &Server{Path: stale, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := s.Listen(); err != nil {
		t.Fatalf("stale socket not replaced: %v", err)
	}
	s.ln.Close()
	s.lock.Close()

	plain := filepath.Join(dir, "plain")
	if err := os.WriteFile(plain, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	s = &Server{Path: plain, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	if err := s.Listen(); err == nil || !strings.Contains(err.Error(), "not a socket") {
		t.Fatalf("regular file: err = %v", err)
	}
}

// Several servers start at once over a stale socket: exactly one wins, and
// its socket is the one left on disk (no loser removes it).
func TestConcurrentListenOneWinner(t *testing.T) {
	for round := range 20 {
		dir := shortTempDir(t)
		path := filepath.Join(dir, SocketName)
		stale, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
		if err != nil {
			t.Fatal(err)
		}
		stale.SetUnlinkOnClose(false)
		stale.Close()

		const n = 8
		servers := make([]*Server, n)
		errs := make([]error, n)
		var wg sync.WaitGroup
		for i := range n {
			servers[i] = &Server{Path: path, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
				Handlers: map[string]Handler{"id": func(json.RawMessage) (any, error) { return i, nil }}}
			wg.Go(func() { errs[i] = servers[i].Listen() })
		}
		wg.Wait()
		winner := -1
		for i, err := range errs {
			switch {
			case err == nil && winner >= 0:
				t.Fatalf("round %d: servers %d and %d both listening", round, winner, i)
			case err == nil:
				winner = i
			case !strings.Contains(err.Error(), "already running"):
				t.Fatalf("round %d: loser err = %v", round, err)
			}
		}
		if winner < 0 {
			t.Fatalf("round %d: no server won: %v", round, errs)
		}
		ctx, cancel := context.WithCancel(context.Background())
		done := make(chan struct{})
		go func() { servers[winner].Serve(ctx); close(done) }()
		var got int
		if err := Call(path, "id", nil, &got); err != nil || got != winner {
			t.Fatalf("round %d: socket answers as %d (%v), want winner %d", round, got, err, winner)
		}
		cancel()
		<-done
		// The lock is released on stop: a new server can start.
		next := &Server{Path: path, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
		if err := next.Listen(); err != nil {
			t.Fatalf("round %d: restart after stop: %v", round, err)
		}
		next.ln.Close()
		next.lock.Close()
	}
}

// A fake terminal peer: Authorize sees the peer's pid and its refusal reaches the CLI; the handler never runs.
func TestAuthorizeRefusesByPeerPID(t *testing.T) {
	var calls atomic.Int32
	path := SocketPath(shortTempDir(t))
	const terminalPID = 4242
	s := &Server{
		Path: path, Log: slog.New(slog.NewTextHandler(io.Discard, nil)),
		Handlers: map[string]Handler{
			"pair.start": func(json.RawMessage) (any, error) { calls.Add(1); return "code", nil },
			"status":     func(json.RawMessage) (any, error) { calls.Add(1); return "ok", nil },
		},
		PeerPID: func(*net.UnixConn) (int, error) { return terminalPID, nil },
		Authorize: func(cmd string, pid int) error {
			if cmd == "pair.start" && pid == terminalPID {
				return errors.New("refused: from a terminal")
			}
			return nil
		},
	}
	if err := s.Listen(); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { s.Serve(ctx); close(done) }()
	defer func() { cancel(); <-done }()
	if err := Call(path, "pair.start", nil, nil); err == nil || !strings.Contains(err.Error(), "refused: from a terminal") {
		t.Fatalf("pair.start from the terminal peer: %v", err)
	}
	if calls.Load() != 0 {
		t.Fatal("refused handler ran")
	}
	var out string
	if err := Call(path, "status", nil, &out); err != nil || out != "ok" {
		t.Fatalf("status: %q %v", out, err)
	}
}

func TestRealPeerPIDIsOwnPID(t *testing.T) {
	path := filepath.Join(shortTempDir(t), "s")
	ln, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		if c, err := net.Dial("unix", path); err == nil {
			time.Sleep(100 * time.Millisecond)
			c.Close()
		}
	}()
	conn, err := ln.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if pid, err := PeerPID(conn); err != nil || pid != os.Getpid() {
		t.Fatalf("pid = %d, %v", pid, err)
	}
}
