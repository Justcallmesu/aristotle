package stocks

import (
	"errors"
	"testing"
)

// TestDecodeVersionedStrictForItsVersion: the supported version is decoded strictly (an unknown field makes the file
// unusable); a newer version leniently (read, reported as newer).
func TestDecodeVersionedStrictForItsVersion(t *testing.T) {
	var doc verdictsDoc
	err := decodeVersioned(verdictsFile, []byte(`{"version":1,"records":[],"extra":1}`), verdictsVersion, &doc)
	var newer *newerFileError
	if err == nil || errors.As(err, &newer) {
		t.Fatalf("version 1 with an unknown field = %v, want an error", err)
	}
	doc = verdictsDoc{}
	err = decodeVersioned(verdictsFile, []byte(`{"version":2,"records":[{"id":"vr_1","code":"BBCA","x":1}],"extra":1}`), verdictsVersion, &doc)
	if !errors.As(err, &newer) || newer.version != 2 || len(doc.Records) != 1 || doc.Records[0].ID != "vr_1" {
		t.Fatalf("newer version = %v, %+v", err, doc)
	}
	if err := decodeVersioned(verdictsFile, []byte(`{"version":1,"records":[]}`), verdictsVersion, &doc); err != nil {
		t.Fatalf("version 1 = %v", err)
	}
}
