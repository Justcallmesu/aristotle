package httpapi

import (
	"log/slog"
	"sync"
	"time"
)

// rejectInterval is the shortest gap between two "pairing attempts rejected"
// log lines.
const rejectInterval = time.Minute

// rejectLog turns a flood of rejected pairing attempts into at most one
// summary line per interval. Each line carries the number of rejections
// since the previous line (rejections after the last line are reported with
// the next one).
type rejectLog struct {
	log      *slog.Logger
	now      func() time.Time
	interval time.Duration

	mu      sync.Mutex
	pending int
	last    time.Time
}

func newRejectLog(log *slog.Logger) *rejectLog {
	return &rejectLog{log: log, now: time.Now, interval: rejectInterval}
}

func (l *rejectLog) note() {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.pending++
	now := l.now()
	if !l.last.IsZero() && now.Sub(l.last) < l.interval {
		return
	}
	l.log.Warn("pairing attempts rejected", "count", l.pending)
	l.pending, l.last = 0, now
}
