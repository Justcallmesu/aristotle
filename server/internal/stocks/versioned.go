package stocks

import (
	"bytes"
	"encoding/json"
	"fmt"
)

// Versioned JSON state files ({"version":N,...}): the decoder stocks-analyses.json and stocks-verdicts.json share.

// newerFileError: the file has a newer version than this server writes. decodeVersioned still fills what this
// version knows; the caller uses it read-only and never writes the file.
type newerFileError struct {
	file      string
	version   int
	supported int
}

func (e *newerFileError) Error() string {
	return fmt.Sprintf("%s: version %d is newer than %d", e.file, e.version, e.supported)
}

// decodeVersioned decodes data, the content of file, into v. Version want is decoded strictly (an unknown field is an
// error); a newer version leniently (unknown fields ignored) and reported with a *newerFileError; any other version
// or bad JSON is an error.
func decodeVersioned(file string, data []byte, want int, v any) error {
	var head struct {
		Version int `json:"version"`
	}
	if err := json.Unmarshal(data, &head); err != nil {
		return fmt.Errorf("%s: %w", file, err)
	}
	switch {
	case head.Version > want:
		if err := json.Unmarshal(data, v); err != nil {
			return fmt.Errorf("%s: %w", file, err)
		}
		return &newerFileError{file: file, version: head.Version, supported: want}
	case head.Version != want:
		return fmt.Errorf("%s: version %d, want %d", file, head.Version, want)
	}
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return fmt.Errorf("%s: %w", file, err)
	}
	return nil
}
