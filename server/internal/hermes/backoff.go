package hermes

import "time"

const (
	backoffBase = 500 * time.Millisecond
	backoffCap  = 30 * time.Second
)

// Backoff is the redial delay after the given number of consecutive failures (0 = first retry):
// doubling from 500 ms, capped at 30 s. Pure, so callers own the loop and the jitter.
func Backoff(attempt int) time.Duration {
	delay := backoffBase
	for i := 0; i < attempt && delay < backoffCap; i++ {
		delay *= 2
	}
	return min(delay, backoffCap)
}
