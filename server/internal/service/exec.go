package service

import (
	"io"
	"os"
	"os/exec"
)

// ExecRunner runs the real commands.
type ExecRunner struct{}

// Run implements Runner.
func (ExecRunner) Run(stdout, stderr io.Writer, name string, args ...string) error {
	c := exec.Command(name, args...)
	c.Stdout, c.Stderr, c.Stdin = stdout, stderr, os.Stdin
	return c.Run()
}
