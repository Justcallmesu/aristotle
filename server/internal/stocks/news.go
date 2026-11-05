package stocks

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"html"
	"log/slog"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// Feed is one RSS source. Categories, when set, keeps only items carrying one of them (or none).
type Feed struct {
	Source     string
	URL        string
	Categories []string
}

// DefaultFeeds are the slice-1 sources (TS0 §4). kontan.co.id/rss is an HTML page; the feed is
// investasi.kontan.co.id/rss. IDX Channel's /rss mixes lifestyle items: only market and economy kept.
var DefaultFeeds = []Feed{
	{Source: "cnbc", URL: "https://www.cnbcindonesia.com/market/rss"},
	{Source: "kontan", URL: "https://investasi.kontan.co.id/rss"},
	{Source: "idxchannel", URL: "https://www.idxchannel.com/rss", Categories: []string{"MARKET NEWS", "ECONOMICS"}},
}

const (
	// NewsInterval is the poll period while someone is looking.
	NewsInterval = 10 * time.Minute
	// NewsIdle stops polling this long after the last news request.
	NewsIdle = 30 * time.Minute
	// NewsRing is how many articles are kept (newest by publish time).
	NewsRing       = 300
	maxSummaryLen  = 300
	newsFetchLimit = 20 * time.Second
)

// Article is one parsed feed item.
type Article struct {
	ID        string
	Source    string
	Title     string
	Link      string
	Published time.Time
	Summary   string
	ImageURL  string
}

// SourceStatus is one entry of the news response's "sources".
type SourceStatus struct {
	Source    string  `json:"source"`
	OK        bool    `json:"ok"`
	FetchedAt *string `json:"fetched_at"` // last successful fetch
	Error     *string `json:"error"`      // reason of the last failed fetch while not ok
}

type rssDoc struct {
	Channel struct {
		Items []rssItem `xml:"item"`
	} `xml:"channel"`
}

type rssItem struct {
	Title       string   `xml:"title"`
	Link        string   `xml:"link"`
	GUID        string   `xml:"guid"`
	PubDate     string   `xml:"pubDate"`
	Description string   `xml:"description"`
	Categories  []string `xml:"category"`
	Enclosure   struct {
		URL  string `xml:"url,attr"`
		Type string `xml:"type,attr"`
	} `xml:"enclosure"`
}

var (
	tagRe   = regexp.MustCompile(`<[^>]*>`)
	imgRe   = regexp.MustCompile(`<img[^>]+src="([^"]+)"`)
	spaceRe = regexp.MustCompile(`\s+`)
)

// parseFeed reads an RSS 2.0 body. Items without a title, an http(s) link or a valid pubDate are dropped.
func parseFeed(f Feed, body []byte) ([]Article, error) {
	var doc rssDoc
	dec := xml.NewDecoder(bytes.NewReader(body))
	dec.Strict = false
	dec.Entity = xml.HTMLEntity
	if err := dec.Decode(&doc); err != nil {
		return nil, err
	}
	var out []Article
	for _, it := range doc.Channel.Items {
		a, ok := articleFrom(f, it)
		if ok {
			out = append(out, a)
		}
	}
	return out, nil
}

func articleFrom(f Feed, it rssItem) (Article, bool) {
	link := strings.TrimSpace(it.Link)
	if link == "" {
		link = strings.TrimSpace(it.GUID)
	}
	title := clean(it.Title)
	pub, err := parsePubDate(it.PubDate)
	if title == "" || err != nil || !(strings.HasPrefix(link, "https://") || strings.HasPrefix(link, "http://")) {
		return Article{}, false
	}
	if len(f.Categories) > 0 && len(it.Categories) > 0 && !slices.ContainsFunc(it.Categories, func(c string) bool {
		return slices.Contains(f.Categories, strings.ToUpper(strings.TrimSpace(c)))
	}) {
		return Article{}, false
	}
	img := it.Enclosure.URL
	if img == "" || !strings.HasPrefix(it.Enclosure.Type, "image/") {
		img = ""
		if m := imgRe.FindStringSubmatch(it.Description); m != nil {
			img = html.UnescapeString(m[1])
		}
	}
	if !(strings.HasPrefix(img, "https://") || strings.HasPrefix(img, "http://")) {
		img = "" // javascript:, data: and the like never reach the phone
	}
	sum := sha256.Sum256([]byte(link))
	return Article{ID: hex.EncodeToString(sum[:6]), Source: f.Source, Title: title, Link: link, Published: pub,
		Summary: truncate(clean(it.Description), maxSummaryLen), ImageURL: img}, true
}

func parsePubDate(s string) (time.Time, error) {
	s = strings.TrimSpace(s)
	for _, layout := range []string{time.RFC1123Z, time.RFC1123, "Mon, 2 Jan 2006 15:04:05 -0700"} {
		if t, err := time.Parse(layout, s); err == nil {
			return t, nil
		}
	}
	return time.Time{}, errors.New("bad pubDate")
}

// clean strips tags, decodes entities and collapses white space.
func clean(s string) string {
	s = html.UnescapeString(tagRe.ReplaceAllString(s, " "))
	s = strings.NewReplacer("\u200b", "", "\u00a0", " ").Replace(s)
	return strings.TrimSpace(spaceRe.ReplaceAllString(s, " "))
}

func truncate(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	r := []rune(s)
	return strings.TrimSpace(string(r[:n-1])) + "…"
}

// News keeps a ring of recent articles from the feeds. It polls only while devices ask: the first
// request after a quiet spell fetches at once, then every Interval until nobody asked for Idle.
type News struct {
	Feeds    []Feed
	Fetch    func(ctx context.Context, url string) ([]byte, error)
	Now      func() time.Time
	Log      *slog.Logger
	Interval time.Duration
	Idle     time.Duration
	After    func(time.Duration) <-chan time.Time // tests drive the poll timer

	mu        sync.Mutex
	items     []Article // newest first
	status    map[string]*SourceStatus
	lastReq   time.Time
	lastFetch time.Time
	polling   bool
	inflight  chan struct{} // closed when the running refresh ends
	stop      chan struct{}
	closed    bool // Close ran: no poller starts again
}

func (n *News) now() time.Time {
	if n.Now != nil {
		return n.Now()
	}
	return time.Now()
}

func (n *News) interval() time.Duration { return cmpOr(n.Interval, NewsInterval) }
func (n *News) idle() time.Duration     { return cmpOr(n.Idle, NewsIdle) }

func cmpOr(d, def time.Duration) time.Duration {
	if d > 0 {
		return d
	}
	return def
}

func (n *News) after(d time.Duration) <-chan time.Time {
	if n.After != nil {
		return n.After(d)
	}
	return time.After(d)
}

// Polling reports whether the background poller runs.
func (n *News) Polling() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.polling
}

// Items records a request, refreshes when the ring is older than Interval (waiting at most until
// ctx ends), keeps the poller alive and returns the ring and the per-source state.
func (n *News) Items(ctx context.Context) ([]Article, []SourceStatus) {
	n.mu.Lock()
	n.lastReq = n.now()
	stale := n.lastFetch.IsZero() || n.now().Sub(n.lastFetch) >= n.interval()
	if !n.polling && !n.closed {
		n.polling = true
		if n.stop == nil {
			n.stop = make(chan struct{})
		}
		go n.poll(n.stop)
	}
	n.mu.Unlock()
	if stale {
		n.refresh(ctx)
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	return slices.Clone(n.items), n.sources()
}

func (n *News) sources() []SourceStatus {
	out := make([]SourceStatus, 0, len(n.Feeds))
	for _, f := range n.Feeds {
		if st, ok := n.status[f.Source]; ok {
			out = append(out, *st)
		} else {
			out = append(out, SourceStatus{Source: f.Source})
		}
	}
	return out
}

func (n *News) poll(stop chan struct{}) {
	for {
		select {
		case <-n.after(n.interval()):
		case <-stop:
			n.mu.Lock()
			n.polling = false
			n.mu.Unlock()
			return
		}
		n.mu.Lock()
		if n.now().Sub(n.lastReq) > n.idle() {
			n.polling = false
			n.mu.Unlock()
			return
		}
		n.mu.Unlock()
		n.refresh(context.Background())
	}
}

// Close stops the poller; a request that is still in flight cannot start it again.
func (n *News) Close() {
	n.mu.Lock()
	defer n.mu.Unlock()
	n.closed = true
	if n.stop != nil {
		close(n.stop)
		n.stop = nil
	}
}

// refresh fetches every feed once; a refresh already running is joined instead.
func (n *News) refresh(ctx context.Context) {
	n.mu.Lock()
	if ch := n.inflight; ch != nil {
		n.mu.Unlock()
		select {
		case <-ch:
		case <-ctx.Done():
		}
		return
	}
	done := make(chan struct{})
	n.inflight = done
	n.mu.Unlock()
	go func() {
		defer close(done)
		n.fetchAll()
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
}

func (n *News) fetchAll() {
	ctx, cancel := context.WithTimeout(context.Background(), newsFetchLimit)
	defer cancel()
	type result struct {
		items []Article
		err   error
	}
	results := make([]result, len(n.Feeds))
	var wg sync.WaitGroup
	for i, f := range n.Feeds {
		wg.Go(func() {
			body, err := n.Fetch(ctx, f.URL)
			if err == nil {
				results[i].items, err = parseFeed(f, body)
			}
			results[i].err = err
		})
	}
	wg.Wait()
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.status == nil {
		n.status = map[string]*SourceStatus{}
	}
	now := n.now()
	for i, f := range n.Feeds {
		st := n.status[f.Source]
		if st == nil {
			st = &SourceStatus{Source: f.Source}
			n.status[f.Source] = st
		}
		if err := results[i].err; err != nil {
			reason := feedErrorReason(err)
			st.OK, st.Error = false, &reason
			if n.Log != nil {
				n.Log.Warn("stocks: news feed failed", "source", f.Source, "reason", reason)
			}
			continue
		}
		st.OK, st.Error, st.FetchedAt = true, nil, rfc(now)
		n.merge(results[i].items)
	}
	n.lastFetch, n.inflight = now, nil
}

func feedErrorReason(err error) string {
	var ue *UpstreamError
	var be *BlockedError
	switch {
	case errors.As(err, &ue):
		return ue.Reason
	case errors.As(err, &be):
		return "http 429"
	case errors.Is(err, ErrUnknownSymbol):
		return "http 404"
	default:
		return "bad feed"
	}
}

// merge adds articles not seen yet (by link) and keeps the newest NewsRing.
func (n *News) merge(items []Article) {
	seen := make(map[string]bool, len(n.items))
	for _, a := range n.items {
		seen[a.Link] = true
	}
	for _, a := range items {
		if !seen[a.Link] {
			seen[a.Link] = true
			n.items = append(n.items, a)
		}
	}
	slices.SortStableFunc(n.items, func(a, b Article) int { return b.Published.Compare(a.Published) })
	if len(n.items) > NewsRing {
		n.items = n.items[:NewsRing]
	}
}
