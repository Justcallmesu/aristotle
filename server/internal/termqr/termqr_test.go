package termqr

import (
	"flag"
	"os"
	"strings"
	"testing"
	"unicode/utf8"

	"rsc.io/qr"
)

var update = flag.Bool("update", false, "rewrite the golden files in testdata")

func render(t *testing.T, text string) (*Code, string) {
	t.Helper()
	c, err := Encode(text)
	if err != nil {
		t.Fatal(err)
	}
	var b strings.Builder
	if err := c.Write(&b); err != nil {
		t.Fatal(err)
	}
	return c, b.String()
}

// A small fixed input (a fake code) renders byte for byte as the golden file (version 2, 25 modules).
func TestRenderGolden(t *testing.T) {
	_, got := render(t, "aristotle://pair?v=1&c=K7Q2M9XD")
	const golden = "testdata/small.golden"
	if *update {
		if err := os.WriteFile(golden, []byte(got), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(golden)
	if err != nil {
		t.Fatal(err)
	}
	if got != string(want) {
		t.Fatalf("render\n%s\nwant\n%s", got, want)
	}
}

// Every module comes back out of the rendered text, the quiet zone is light, and
// each line is as wide as Columns and resets its colours.
func TestRenderIsLosslessWithQuietZone(t *testing.T) {
	text := "aristotle://pair?v=1&c=K7Q2M9XD&fp=7E54D87B0C1F9A426B3E11D08F5C2A974E06B1C8D3F25A690E7BC4D1BCA7EAA7" +
		"&a=https%3A%2F%2F192.168.1.61%3A8760&a=https%3A%2F%2F100.74.61.96%3A8760"
	c, out := render(t, text)
	ref, err := qr.Encode(text, qr.L)
	if err != nil {
		t.Fatal(err)
	}
	if c.Columns() != ref.Size+2*QuietZone {
		t.Fatalf("columns %d for size %d", c.Columns(), ref.Size)
	}
	lines := strings.Split(strings.TrimSuffix(out, "\n"), "\n")
	if want := (ref.Size + 2*QuietZone + 1) / 2; len(lines) != want {
		t.Fatalf("%d lines, want %d", len(lines), want)
	}
	for row, line := range lines {
		body, ok := strings.CutPrefix(line, colours)
		body, ok2 := strings.CutSuffix(body, reset)
		if !ok || !ok2 {
			t.Fatalf("line %d does not set and reset its colours: %q", row, line)
		}
		if n := utf8.RuneCountInString(body); n != c.Columns() {
			t.Fatalf("line %d is %d cells, want %d", row, n, c.Columns())
		}
		for col, r := range []rune(body) {
			x, y := col-QuietZone, 2*row-QuietZone
			top, bottom := r == '█' || r == '▀', r == '█' || r == '▄'
			if top != ref.Black(x, y) || bottom != ref.Black(x, y+1) {
				t.Fatalf("cell (%d,%d) %q, want top %v bottom %v", col, row, r, ref.Black(x, y), ref.Black(x, y+1))
			}
		}
	}
}

func TestEncodeRefusesTooLongText(t *testing.T) {
	if _, err := Encode(strings.Repeat("x", 4000)); err == nil {
		t.Fatal("want an error for text over a version 40 code")
	}
}
