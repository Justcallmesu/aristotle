package app

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"

	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/terminal"
)

// Terminal control commands (docs/terminal.md). Only the CLI on the PC reaches them; nothing on HTTP
// changes the switch or a grant.
const (
	cmdTerminalEnable  = "terminal.enable"
	cmdTerminalDisable = "terminal.disable"
	cmdTerminalStatus  = "terminal.status"
	cmdGrantTerminal   = "devices.grant_terminal"
	cmdUngrantTerminal = "devices.ungrant_terminal"
)

// escalates lists the commands a process inside a remote terminal may not run: each would widen what
// the phone can do (a new pairing that outlives a revoke, the switch, a grant). Disable, ungrant,
// revoke and status stay allowed from anywhere (T19 red-team B2).
func escalates(cmd string) bool {
	return strings.HasPrefix(cmd, "pair.") || cmd == cmdTerminalEnable || cmd == cmdGrantTerminal
}

// authorizeControl refuses an escalating command when the calling process is inside a terminal.
func (s *server) authorizeControl(cmd string, pid int) error {
	if !escalates(cmd) {
		return nil
	}
	how, inside := s.terms.InTerminal(pid)
	if !inside {
		return nil
	}
	s.log.Warn("control command refused from a remote terminal", "cmd", cmd, "pid", pid, "seen_by", how)
	return fmt.Errorf("refused: %s cannot be run from an Aristotle remote terminal (or a process started from one); "+
		"run it in a terminal opened on the PC itself", cmd)
}

type terminalStatusResult struct {
	Enabled bool            `json:"enabled"`
	Granted []grantedDevice `json:"granted"`
	Open    []terminal.Open `json:"open"`
	Changed bool            `json:"changed"`
	Warning string          `json:"warning,omitempty"`
	Device  *devices.Public `json:"device,omitempty"`
}

type grantedDevice struct {
	ID   string `json:"id"`
	Name string `json:"name"` // "" when the device is no longer paired
}

func (s *server) terminalHandlers() map[string]func(json.RawMessage) (any, error) {
	return map[string]func(json.RawMessage) (any, error){
		cmdTerminalEnable:  func(json.RawMessage) (any, error) { return s.setTerminal(true) },
		cmdTerminalDisable: func(json.RawMessage) (any, error) { return s.setTerminal(false) },
		cmdTerminalStatus:  func(json.RawMessage) (any, error) { return s.terminalStatus(false, nil), nil },
		cmdGrantTerminal:   s.grantTerminal,
		cmdUngrantTerminal: s.ungrantTerminal,
	}
}

func (s *server) setTerminal(on bool) (any, error) {
	changed, err := s.grants.SetEnabled(on)
	if err != nil && !changed {
		return nil, err
	}
	s.log.Info("terminal switch", "enabled", on, "changed", changed)
	res := s.terminalStatus(changed, nil)
	if err != nil {
		res.Warning = err.Error()
	}
	return res, nil
}

func (s *server) grantTerminal(raw json.RawMessage) (any, error) {
	dev, err := s.deviceArg(raw)
	if err != nil {
		return nil, err
	}
	changed, err := s.grants.Grant(dev.ID)
	if err != nil {
		return nil, err
	}
	s.log.Info("terminal granted", "device_id", dev.ID, "changed", changed)
	return s.terminalStatus(changed, &dev), nil
}

func (s *server) ungrantTerminal(raw json.RawMessage) (any, error) {
	var args revokeArgs
	if err := decodeArgs(raw, &args); err != nil {
		return nil, err
	}
	changed, err := s.grants.Ungrant(args.ID)
	if err != nil && !changed {
		return nil, err
	}
	if !changed {
		return nil, errors.New("device " + args.ID + " has no terminal grant")
	}
	s.log.Info("terminal grant removed", "device_id", args.ID)
	res := s.terminalStatus(true, &devices.Public{ID: args.ID})
	if err != nil {
		res.Warning = err.Error()
	}
	return res, nil
}

// deviceArg returns the paired device the command names.
func (s *server) deviceArg(raw json.RawMessage) (devices.Public, error) {
	var args revokeArgs
	if err := decodeArgs(raw, &args); err != nil {
		return devices.Public{}, err
	}
	for _, d := range s.devices.List() {
		if d.ID == args.ID {
			return d, nil
		}
	}
	return devices.Public{}, errors.New("no device with id " + args.ID)
}

func (s *server) terminalStatus(changed bool, dev *devices.Public) terminalStatusResult {
	enabled, ids := s.grants.Status()
	names := map[string]string{}
	for _, d := range s.devices.List() {
		names[d.ID] = d.Name
	}
	res := terminalStatusResult{Enabled: enabled, Open: s.terms.List(), Changed: changed, Device: dev,
		Granted: []grantedDevice{}}
	for _, id := range ids {
		res.Granted = append(res.Granted, grantedDevice{ID: id, Name: names[id]})
	}
	return res
}

const terminalUsage = "usage: aristotle-server terminal enable | disable | status"

func runTerminal(args []string, stdout, stderr io.Writer) int {
	cmds := map[string]string{"enable": cmdTerminalEnable, "disable": cmdTerminalDisable, "status": cmdTerminalStatus}
	if len(args) != 1 || cmds[args[0]] == "" {
		fmt.Fprintln(stderr, terminalUsage)
		return 2
	}
	var res terminalStatusResult
	if err := callServer(cmds[args[0]], nil, &res); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	switch args[0] {
	case "enable":
		fmt.Fprintln(stdout, "Remote terminal enabled. A granted phone now gets a shell with FULL access to your account")
		fmt.Fprintln(stdout, "(files, SSH keys, Hermes); revoking the phone later does not undo what was done. See docs/terminal.md.")
		if len(res.Granted) == 0 {
			fmt.Fprintln(stdout, "No device holds a grant yet: aristotle-server devices grant-terminal <id>")
		}
	case "disable":
		fmt.Fprintln(stdout, "Remote terminal disabled. Open terminals were closed and their processes killed.")
	}
	printTerminalStatus(stdout, res)
	printWarning(stderr, res.Warning)
	return 0
}

func printTerminalStatus(w io.Writer, res terminalStatusResult) {
	state := "disabled"
	if res.Enabled {
		state = "enabled"
	}
	fmt.Fprintf(w, "Terminal: %s\n", state)
	if len(res.Granted) == 0 {
		fmt.Fprintln(w, "Granted devices: none")
	} else {
		fmt.Fprintln(w, "Granted devices:")
		tw := tabwriter.NewWriter(w, 0, 4, 2, ' ', 0)
		for _, g := range res.Granted {
			name := sanitize(g.Name)
			if name == "" {
				name = "(no longer paired)"
			}
			fmt.Fprintf(tw, "  %s\t%s\n", g.ID, name)
		}
		_ = tw.Flush()
	}
	if len(res.Open) == 0 {
		fmt.Fprintln(w, "Open terminals: none")
		return
	}
	fmt.Fprintln(w, "Open terminals:")
	tw := tabwriter.NewWriter(w, 0, 4, 2, ' ', 0)
	for _, o := range res.Open {
		fmt.Fprintf(tw, "  %s\t%s\t%s\tsince %s\tpid %d\n", o.ID, o.DeviceID, o.Listener, stamp(o.Since), o.PID)
	}
	_ = tw.Flush()
}

func printWarning(w io.Writer, warning string) {
	if warning != "" {
		fmt.Fprintln(w, "warning:", warning)
	}
}

func devicesGrant(id string, grant bool, stdout, stderr io.Writer) int {
	cmd := cmdGrantTerminal
	if !grant {
		cmd = cmdUngrantTerminal
	}
	var res terminalStatusResult
	if err := callServer(cmd, revokeArgs{ID: id}, &res); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	switch {
	case grant && !res.Changed:
		fmt.Fprintf(stdout, "%s already holds a terminal grant.\n", id)
	case grant:
		fmt.Fprintf(stdout, "Granted the remote terminal to %s (%s): it gets a shell with full access to your account", id, sanitize(res.Device.Name))
		if res.Enabled {
			fmt.Fprintln(stdout, ".")
		} else {
			fmt.Fprintln(stdout, "\nonce the terminal is enabled (aristotle-server terminal enable).")
		}
	default:
		fmt.Fprintf(stdout, "Removed the terminal grant of %s; its open terminals were closed.\n", id)
	}
	printWarning(stderr, res.Warning)
	return 0
}
