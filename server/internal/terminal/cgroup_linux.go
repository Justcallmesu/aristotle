//go:build linux

package terminal

import (
	"bytes"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const (
	cgroupRoot = "/sys/fs/cgroup"
	// cgroup2Magic is CGROUP2_SUPER_MAGIC (linux/magic.h).
	cgroup2Magic = 0x63677270
	// termCgroupPrefix names each terminal's child cgroup inside the service's own.
	termCgroupPrefix = "aristotle-term-"
	cgroupEmptyWait  = 2 * time.Second
)

// cgroupParent is the service's own cgroup v2 directory, delegated to it (systemd Delegate=yes), under
// which each terminal gets a child. The server itself stays in the parent: it enables no controllers in
// cgroup.subtree_control, so the no-internal-process rule does not apply.
type cgroupParent struct{ dir string }

// termCgroup is one terminal's cgroup. fd is open for clone3 until the shell has started.
type termCgroup struct {
	dir string
	fd  int
}

// detectCgroup returns the delegated parent, or nil and why there is none. delegatedUnit names a
// systemd unit known to have Delegate=yes (its unit file says so): its cgroup counts as delegated even
// without the xattr, which systemd's user manager does not set (seen on systemd 261).
func detectCgroup(delegatedUnit string) (*cgroupParent, string) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(cgroupRoot, &st); err != nil || st.Type != cgroup2Magic {
		return nil, "no cgroup v2 hierarchy at " + cgroupRoot
	}
	path, err := procCgroup(os.Getpid())
	if err != nil {
		return nil, "no cgroup v2 entry for the server process"
	}
	return delegatedParent(filepath.Join(cgroupRoot, path), delegatedUnit)
}

func delegatedParent(dir, delegatedUnit string) (*cgroupParent, string) {
	if _, err := os.Stat(filepath.Join(dir, "cgroup.kill")); err != nil {
		return nil, "no cgroup.kill (needs Linux 5.14 or later)"
	}
	if !delegated(dir) && (delegatedUnit == "" || filepath.Base(dir) != delegatedUnit) {
		return nil, "the service's cgroup is not delegated (unit without Delegate=yes, or not run by systemd)"
	}
	if err := syscall.Access(dir, 2 /* W_OK */); err != nil {
		return nil, "the service's cgroup is not writable"
	}
	return &cgroupParent{dir: dir}, ""
}

// delegated: systemd marks a delegated cgroup with the xattr user.delegate (user manager) or
// trusted.delegate (system manager) set to "1".
func delegated(dir string) bool {
	for _, attr := range []string{"user.delegate", "trusted.delegate"} {
		buf := make([]byte, 8)
		if n, err := syscall.Getxattr(dir, attr, buf); err == nil && string(buf[:n]) == "1" {
			return true
		}
	}
	return false
}

// create makes the terminal's cgroup and opens it for CLONE_INTO_CGROUP.
func (p *cgroupParent) create(id string) (*termCgroup, error) {
	dir := filepath.Join(p.dir, termCgroupPrefix+id)
	if err := os.Mkdir(dir, 0o755); err != nil {
		return nil, fmt.Errorf("cgroup: %w", err)
	}
	fd, err := syscall.Open(dir, syscall.O_RDONLY|syscall.O_DIRECTORY|syscall.O_CLOEXEC, 0)
	if err != nil {
		_ = os.Remove(dir)
		return nil, fmt.Errorf("cgroup: %w", err)
	}
	return &termCgroup{dir: dir, fd: fd}, nil
}

// holds reports whether pid lives in a terminal's cgroup (or below it).
func (p *cgroupParent) holds(pid int) (bool, error) {
	path, err := procCgroup(pid)
	if err != nil {
		return false, err
	}
	return strings.HasPrefix(filepath.Join(cgroupRoot, path), filepath.Join(p.dir, termCgroupPrefix)), nil
}

// sweep kills and removes terminal cgroups left by an earlier run of the server (a crash).
func (p *cgroupParent) sweep() int {
	entries, err := os.ReadDir(p.dir)
	if err != nil {
		return 0
	}
	n := 0
	for _, e := range entries {
		if e.IsDir() && strings.HasPrefix(e.Name(), termCgroupPrefix) {
			c := &termCgroup{dir: filepath.Join(p.dir, e.Name()), fd: -1}
			c.destroy()
			n++
		}
	}
	return n
}

// closeFD releases the clone3 descriptor once the shell is started.
func (c *termCgroup) closeFD() {
	if c.fd >= 0 {
		_ = syscall.Close(c.fd)
		c.fd = -1
	}
}

// kill SIGKILLs every process in the cgroup and its descendants (cgroup.kill).
func (c *termCgroup) kill() error {
	f, err := os.OpenFile(filepath.Join(c.dir, "cgroup.kill"), os.O_WRONLY, 0)
	if err != nil {
		return err
	}
	_, err = f.WriteString("1")
	return errors.Join(err, f.Close())
}

// populated reports whether any process is left in the cgroup or below it.
func (c *termCgroup) populated() bool {
	b, err := os.ReadFile(filepath.Join(c.dir, "cgroup.events"))
	if err != nil {
		return false
	}
	return bytes.Contains(b, []byte("populated 1"))
}

// procs counts the processes directly in the cgroup.
func (c *termCgroup) procs() int {
	b, err := os.ReadFile(filepath.Join(c.dir, "cgroup.procs"))
	if err != nil {
		return 0
	}
	return len(strings.Fields(string(b)))
}

// destroy kills everything in the cgroup, waits until it is empty, and removes it (child cgroups a
// terminal process made first). It returns how many processes the kill found directly in the cgroup.
func (c *termCgroup) destroy() int {
	c.closeFD()
	n := c.procs()
	_ = c.kill()
	for deadline := time.Now().Add(cgroupEmptyWait); c.populated() && time.Now().Before(deadline); {
		time.Sleep(10 * time.Millisecond)
	}
	removeTree(c.dir)
	return n
}

// removeTree rmdirs a cgroup directory, children first. cgroupfs refuses rmdir on files, so only
// directories are visited.
func removeTree(dir string) {
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if e.IsDir() {
			removeTree(filepath.Join(dir, e.Name()))
		}
	}
	_ = syscall.Rmdir(dir)
}
