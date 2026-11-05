package stocks

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

// Screener settings (spec Q86/Q111, TS4 §3-§4): every setup on/off with tunable thresholds, plus the liquidity
// floor. Kept in stocks-screener.json (not stocks.json: that file accepts version 1 only, and a newer version
// there would switch off an older binary's watchlist). The scan interval is the screener.scan job's every_min.

// BreakoutSettings: P above the highest high of Lookback sessions with paced volume ≥ VolMult × the average.
type BreakoutSettings struct {
	On       bool    `json:"on"`
	Lookback int     `json:"lookback"`
	VolMult  float64 `json:"vol_mult"`
}

// VolumeSpikeSettings: paced volume ≥ Mult × the 20-session average.
type VolumeSpikeSettings struct {
	On   bool    `json:"on"`
	Mult float64 `json:"mult"`
}

// MACrossSettings: MA20 crossing MA50 (fixed periods).
type MACrossSettings struct {
	On bool `json:"on"`
}

// RSIReboundSettings: RSI14 crossing up through Level.
type RSIReboundSettings struct {
	On    bool    `json:"on"`
	Level float64 `json:"level"`
}

// GapSettings: the day's open at least MinPct above (Up) or below (Down) the last close.
type GapSettings struct {
	On     bool    `json:"on"`
	MinPct float64 `json:"min_pct"`
	Up     bool    `json:"up"`
	Down   bool    `json:"down"`
}

// SetupSettings are the five setups.
type SetupSettings struct {
	Breakout    BreakoutSettings    `json:"breakout"`
	VolumeSpike VolumeSpikeSettings `json:"volume_spike"`
	MACross     MACrossSettings     `json:"ma_cross"`
	RSIRebound  RSIReboundSettings  `json:"rsi_rebound"`
	Gap         GapSettings         `json:"gap"`
}

// ScreenerSettings is GET/PUT /v1/stocks/screener/settings.
type ScreenerSettings struct {
	MinValueBn float64       `json:"min_value_bn"` // mean daily value traded over 20 sessions, Rp bn; 0 = off
	Setups     SetupSettings `json:"setups"`
}

// DefaultScreenerSettings are the Q111/TS4 starting values.
func DefaultScreenerSettings() ScreenerSettings {
	return ScreenerSettings{MinValueBn: 5, Setups: SetupSettings{
		Breakout:    BreakoutSettings{On: true, Lookback: 20, VolMult: 1.5},
		VolumeSpike: VolumeSpikeSettings{On: true, Mult: 2.0},
		MACross:     MACrossSettings{On: true},
		RSIRebound:  RSIReboundSettings{On: true, Level: 30},
		Gap:         GapSettings{On: true, MinPct: 2.0, Up: true, Down: true},
	}}
}

func inRange(v, lo, hi float64) bool { return v >= lo && v <= hi }

// valid checks the TS4 §3 ranges.
func (s ScreenerSettings) valid() bool {
	st := s.Setups
	return inRange(s.MinValueBn, 0, 100) &&
		st.Breakout.Lookback >= 10 && st.Breakout.Lookback <= 60 && inRange(st.Breakout.VolMult, 1, 5) &&
		inRange(st.VolumeSpike.Mult, 1.5, 10) &&
		inRange(st.RSIRebound.Level, 15, 45) &&
		inRange(st.Gap.MinPct, 0.5, 10)
}

// decodeScreenerSettings reads a PUT body: one object ≤ 4 KiB, no unknown field, EVERY field present (the
// whole object is replaced), values in range.
func decodeScreenerSettings(r *http.Request) (ScreenerSettings, error) {
	data, err := io.ReadAll(io.LimitReader(r.Body, maxBodyBytesIn+1))
	if err != nil || len(data) > maxBodyBytesIn {
		return ScreenerSettings{}, errBadRequest
	}
	var s ScreenerSettings
	if err := decodeStrict(data, &s); err != nil {
		return ScreenerSettings{}, errBadRequest
	}
	var got any
	if err := json.Unmarshal(data, &got); err != nil {
		return ScreenerSettings{}, errBadRequest
	}
	full, _ := json.Marshal(s)
	var want any
	_ = json.Unmarshal(full, &want)
	if !sameKeys(want, got) || !s.valid() {
		return ScreenerSettings{}, errBadRequest
	}
	return s, nil
}

// decodeStrict decodes exactly one JSON value with no unknown fields.
func decodeStrict(data []byte, v any) error {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return errors.New("data after the value")
	}
	return nil
}

// sameKeys reports whether got has every object key of want, recursively (got's extra keys are refused by
// decodeStrict already).
func sameKeys(want, got any) bool {
	w, ok := want.(map[string]any)
	if !ok {
		return true
	}
	g, ok := got.(map[string]any)
	if !ok {
		return false
	}
	for k, wv := range w {
		gv, ok := g[k]
		if !ok || !sameKeys(wv, gv) {
			return false
		}
	}
	return true
}
