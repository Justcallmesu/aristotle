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
	"github.com/justcallmesu/aristotle/server/internal/termqr"
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
	noQR := fs.Bool("no-qr", false, "print only the code, addresses and fingerprint, no QR code")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() != 0 {
		fmt.Fprintln(stderr, "usage: aristotle-server pair [--name NAME] [--no-qr]")
		return 2
	}
	var res pairStartResult
	if err := callServer(cmdPairStart, pairStartArgs{Name: *name}, &res); err != nil {
		fmt.Fprintln(stderr, "error:", err)
		return 1
	}
	printPairing(stdout, res, time.Now(), !*noQR)
	return 0
}

// printPairing prints the pairing details to the pair command's stdout (never
// to the server log): the QR code when withQR, then the same data to type in.
func printPairing(w io.Writer, res pairStartResult, now time.Time, withQR bool) {
	if withQR {
		printQR(w, res)
	}
	left := res.ExpiresAt.Sub(now).Round(time.Second)
	fmt.Fprintf(w, "Pairing code:  %s\n", res.Code)
	fmt.Fprintf(w, "Expires:       %s (in %s); single use; %d wrong tries lock out that address\n\n",
		res.ExpiresAt.Local().Format("15:04:05"), left, pairing.MaxAttemptsPerPeer)
	fmt.Fprintln(w, "Addresses (enter one in the app):")
	printAddrs(w, res.Addresses)
	fmt.Fprintln(w, "\nCertificate fingerprint (the app shows it for every address; they must match):")
	fmt.Fprintf(w, "  %s\n", res.Fingerprint)
}

// qrMaxColumns keeps the QR code inside an 80-column terminal: a wrapped line breaks the code.
const qrMaxColumns = 80

// printQR prints the pairing QR code (protocol §11.4) with every address, at
// most pairing.MaxPayloadAddresses. When that would be wider than
// qrMaxColumns, the last addresses are left out until it fits: the phone needs
// one that answers, and the pair response gives it the whole list.
func printQR(w io.Writer, res pairStartResult) {
	if len(res.Addresses) == 0 {
		return // the address list below says why
	}
	urls := make([]string, len(res.Addresses))
	for i, a := range res.Addresses {
		urls[i] = a.URL
	}
	for n := min(len(urls), pairing.MaxPayloadAddresses); n > 0; n-- {
		code, err := qrFor(res, urls[:n])
		if err != nil {
			fmt.Fprintf(w, "No QR code (%v); type the code in the app.\n\n", err)
			return
		}
		if code.Columns() > qrMaxColumns {
			continue
		}
		fmt.Fprintln(w, "Scan with the app (Scan QR code):")
		_ = code.Write(w)
		if n < len(urls) {
			fmt.Fprintf(w, "The QR code carries the first %d of %d addresses; the app gets the rest when it pairs.\n", n, len(urls))
		}
		fmt.Fprintln(w, "\nOr type it in:")
		return
	}
	fmt.Fprintf(w, "No QR code (it would not fit %d columns); type the code in the app.\n\n", qrMaxColumns)
}

func qrFor(res pairStartResult, urls []string) (*termqr.Code, error) {
	text, err := pairing.Payload(res.Code, res.Fingerprint, urls)
	if err != nil {
		return nil, err
	}
	return termqr.Encode(text)
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
		return "Tailscale, HTTPS"
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
