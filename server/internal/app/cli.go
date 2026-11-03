package app

import (
	"flag"
	"fmt"
	"io"
	"strings"
	"text/tabwriter"
	"time"
	"unicode"

	"github.com/justcallmesu/aristotle/server/internal/config"
	"github.com/justcallmesu/aristotle/server/internal/control"
	"github.com/justcallmesu/aristotle/server/internal/devices"
	"github.com/justcallmesu/aristotle/server/internal/pairing"
)

// callServer sends one control command to the running server.
func callServer(cmd string, args, out any) error {
	dir, err := config.StateDir()
	if err != nil {
		return err
	}
	return control.Call(control.SocketPath(dir), cmd, args, out)
}

func runPair(args []string, stdout, stderr io.Writer) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	fs.SetOutput(stderr)
	name := fs.String("name", "", "name for the paired device (default: the name the phone sends)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() != 0 {
		fmt.Fprintln(stderr, "usage: aristotle-server pair [--name NAME]")
		return 2
	}
	var res pairStartResult
	if err := callServer(cmdPairStart, pairStartArgs{Name: *name}, &res); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	printPairing(stdout, res, time.Now())
	return 0
}

func printPairing(w io.Writer, res pairStartResult, now time.Time) {
	left := res.ExpiresAt.Sub(now).Round(time.Second)
	fmt.Fprintf(w, "Pairing code:  %s\n", res.Code)
	fmt.Fprintf(w, "Expires:       %s (in %s); single use; %d wrong tries lock out that address\n\n",
		res.ExpiresAt.Local().Format("15:04:05"), left, pairing.MaxAttemptsPerPeer)
	fmt.Fprintln(w, "Addresses (enter one in the app):")
	printAddrs(w, res.Addresses)
	fmt.Fprintln(w, "\nCertificate fingerprint (the app shows it on Wi-Fi/LAN; they must match):")
	fmt.Fprintf(w, "  %s\n", res.Fingerprint)
}

// printAddrs prints one line per listener in the server's order (default
// route first, Tailscale last) with its interface name.
func printAddrs(w io.Writer, addrs []pairAddr) {
	if len(addrs) == 0 {
		fmt.Fprintln(w, "  none: no LAN or Tailscale listener is up (see the server log)")
		return
	}
	tw := tabwriter.NewWriter(w, 0, 4, 2, ' ', 0)
	for _, a := range addrs {
		fmt.Fprintf(tw, "  %s\t%s\t%s\n", a.URL, sanitize(a.Iface), addrNote(a))
	}
	_ = tw.Flush()
}

func addrNote(a pairAddr) string {
	switch {
	case a.Kind == "tailscale":
		return "Tailscale, HTTP"
	case a.DefaultRoute:
		return "LAN, HTTPS, default route"
	}
	return "LAN, HTTPS"
}

const devicesUsage = "usage: aristotle-server devices list | devices revoke <id>"

func runDevices(args []string, stdout, stderr io.Writer) int {
	switch {
	case len(args) == 1 && args[0] == "list":
		return devicesList(stdout, stderr)
	case len(args) == 2 && args[0] == "revoke":
		return devicesRevoke(args[1], stdout, stderr)
	}
	fmt.Fprintln(stderr, devicesUsage)
	return 2
}

func devicesList(stdout, stderr io.Writer) int {
	var list []devices.Public
	if err := callServer(cmdDevicesList, nil, &list); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	if len(list) == 0 {
		fmt.Fprintln(stdout, "No paired devices.")
		return 0
	}
	tw := tabwriter.NewWriter(stdout, 0, 4, 2, ' ', 0)
	fmt.Fprintln(tw, "ID\tNAME\tPAIRED\tLAST SEEN")
	for _, d := range list {
		fmt.Fprintf(tw, "%s\t%s\t%s\t%s\n", d.ID, sanitize(d.Name), stamp(d.CreatedAt), stamp(d.LastSeenAt))
	}
	_ = tw.Flush()
	return 0
}

func devicesRevoke(id string, stdout, stderr io.Writer) int {
	var dev devices.Public
	if err := callServer(cmdDevicesRevoke, revokeArgs{ID: id}, &dev); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	fmt.Fprintf(stdout, "Revoked %s (%s). Its token stops working on the next request.\n", dev.ID, sanitize(dev.Name))
	return 0
}

func stamp(t time.Time) string { return t.Local().Format("2006-01-02 15:04") }

// sanitize keeps a stored name from breaking the table or reordering the
// terminal text (pairing already strips these; this is a second guard).
func sanitize(s string) string {
	return strings.Map(func(r rune) rune {
		if unicode.In(r, unicode.Cc, unicode.Cf, unicode.Zl, unicode.Zp) {
			return '?'
		}
		return r
	}, s)
}
