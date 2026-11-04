package discovery

import "time"

// Start-failure retry: the first retry after retryFirst, doubling up to retryMax.
const (
	retryFirst = 10 * time.Second
	retryMax   = 5 * time.Minute
)

// backoff tracks the failed starts of one interface.
type backoff struct {
	fails   int
	next    time.Time // no attempt before this
	lastErr string
}

// due reports whether a new attempt may run at now.
func (b *backoff) due(now time.Time) bool { return !now.Before(b.next) }

// failed records a failure at now. It returns the delay before the next
// attempt and whether the error is new (first failure, or a different error),
// i.e. worth a warning; a repeat of the same error is not.
func (b *backoff) failed(now time.Time, err error) (delay time.Duration, fresh bool) {
	delay = retryFirst
	for i := 0; i < b.fails && delay < retryMax; i++ {
		delay *= 2
	}
	delay = min(delay, retryMax)
	b.fails++
	b.next = now.Add(delay)
	fresh = err.Error() != b.lastErr
	b.lastErr = err.Error()
	return delay, fresh
}
