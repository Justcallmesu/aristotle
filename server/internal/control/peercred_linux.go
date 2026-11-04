//go:build linux

package control

import (
	"net"
	"syscall"
)

// PeerUID reads the connecting process's uid with SO_PEERCRED.
func PeerUID(conn *net.UnixConn) (int, error) {
	cred, err := peerCred(conn)
	if err != nil {
		return 0, err
	}
	return int(cred.Uid), nil
}

// PeerPID reads the connecting process's pid with SO_PEERCRED (the process that connected).
func PeerPID(conn *net.UnixConn) (int, error) {
	cred, err := peerCred(conn)
	if err != nil {
		return 0, err
	}
	return int(cred.Pid), nil
}

func peerCred(conn *net.UnixConn) (*syscall.Ucred, error) {
	raw, err := conn.SyscallConn()
	if err != nil {
		return nil, err
	}
	var cred *syscall.Ucred
	var credErr error
	if err := raw.Control(func(fd uintptr) {
		cred, credErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	}); err != nil {
		return nil, err
	}
	if credErr != nil {
		return nil, credErr
	}
	return cred, nil
}
