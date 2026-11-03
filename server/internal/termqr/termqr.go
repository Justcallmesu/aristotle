// Package termqr draws QR codes in a terminal with Unicode half blocks: one
// character cell holds two modules stacked, so a code is as many columns wide
// as it has modules (plus the quiet zone) and half as many lines tall.
package termqr

import (
	"io"
	"strings"

	"rsc.io/qr"
)

// QuietZone is the light border on each side, in modules (the QR standard's minimum).
const QuietZone = 4

// Dark modules are drawn as black on a bright white background whatever the
// terminal's colours, so the code reads dark-on-light on dark and light themes
// alike; every line resets the colours at its end.
const (
	colours = "\x1b[30;107m"
	reset   = "\x1b[0m"
)

// Code is an encoded QR code ready to print.
type Code struct {
	qr *qr.Code
}

// Encode encodes text at the lowest error correction level (L): the code is
// read off a screen, and L keeps it narrowest.
func Encode(text string) (*Code, error) {
	c, err := qr.Encode(text, qr.L)
	if err != nil {
		return nil, err
	}
	return &Code{qr: c}, nil
}

// Columns is the printed width in character cells, quiet zone included.
func (c *Code) Columns() int { return c.qr.Size + 2*QuietZone }

// Write prints the code, quiet zone included.
func (c *Code) Write(w io.Writer) error {
	var b strings.Builder
	end := c.qr.Size + QuietZone
	for y := -QuietZone; y < end; y += 2 {
		b.WriteString(colours)
		for x := -QuietZone; x < end; x++ {
			// Black is false outside the code: the quiet zone and the half row below an odd size.
			b.WriteRune(cell(c.qr.Black(x, y), c.qr.Black(x, y+1)))
		}
		b.WriteString(reset + "\n")
	}
	_, err := io.WriteString(w, b.String())
	return err
}

// cell is the character for a top and a bottom module (foreground = dark).
func cell(top, bottom bool) rune {
	switch {
	case top && bottom:
		return '█'
	case top:
		return '▀'
	case bottom:
		return '▄'
	}
	return ' '
}
