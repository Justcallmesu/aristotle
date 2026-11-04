package stocks

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestParseFeeds(t *testing.T) {
	tests := []struct {
		feed     Feed
		file     string
		n        int
		first    string
		image    string
		pubWIB   string
		noInText string
	}{
		{DefaultFeeds[0], "rss_cnbc.xml", 6, "Fundamental Bisnis Kuat, SeaBank Konsisten Jaga Pertumbuhan Positif",
			"https://akcdn.detik.net.id/visual/2026/11/04/seabank-1793809392522_169.jpeg?w=1200&q=90", "2026-11-04T23:23:39+07:00", "<img"},
		{DefaultFeeds[1], "rss_kontan.xml", 8, "Wall Street Menguat Rabu (4/11), Demokrat Kuasai Kongres AS, Saham Data Center Lesu",
			"", "2026-11-04T21:54:10+07:00", "&nbsp;"},
		// 10 items, 2 ECOTAINMENT left out.
		{DefaultFeeds[2], "rss_idxchannel.xml", 8, "Ongkos Transportasi Habiskan 30 Persen UMR, Pengembangan Transportasi Publik Jadi Sorotan",
			"https://img.idxchannel.com/media/439/images/idx/2026/11/04/transportasi_publik_foto_mei_sada.jpg", "2026-11-05T00:02:00+07:00", "&lt;img"},
	}
	for _, tt := range tests {
		t.Run(tt.feed.Source, func(t *testing.T) {
			items, err := parseFeed(tt.feed, readFixture(t, tt.file))
			if err != nil || len(items) != tt.n {
				t.Fatalf("items = %d, %v", len(items), err)
			}
			a := items[0]
			if a.Title != tt.first || a.ImageURL != tt.image || a.Published.In(WIB).Format(time.RFC3339) != tt.pubWIB ||
				a.Source != tt.feed.Source || len(a.ID) != 12 || !strings.HasPrefix(a.Link, "https://") {
				t.Fatalf("first = %+v", a)
			}
			for _, it := range items {
				if it.Summary == "" && tt.feed.Source != "cnbc" || strings.Contains(it.Summary, tt.noInText) || strings.Contains(it.Summary, "<") {
					t.Errorf("summary %q", it.Summary)
				}
				if strings.Contains(it.Title, "Pokémon") {
					t.Error("ECOTAINMENT item kept")
				}
			}
		})
	}
	// kontan.co.id/rss is an HTML page: it must give no items.
	if items, _ := parseFeed(DefaultFeeds[1], []byte("<html><body><p>not a feed</p></body></html>")); len(items) != 0 {
		t.Fatal("html page gave items")
	}
}

func TestNewsMergeDedupesAndCaps(t *testing.T) {
	n := &News{}
	base := time.Date(2026, 11, 5, 0, 0, 0, 0, WIB)
	var batch []Article
	for i := range 350 {
		batch = append(batch, Article{Link: fmt.Sprintf("https://x/%d", i), Published: base.Add(time.Duration(i) * time.Minute)})
	}
	n.merge(batch[:10])
	n.merge(batch[:10])
	if len(n.items) != 10 || n.items[0].Link != "https://x/9" {
		t.Fatalf("dedupe: %d items, first %s", len(n.items), n.items[0].Link)
	}
	n.merge(batch)
	if len(n.items) != NewsRing || n.items[0].Link != "https://x/349" || n.items[NewsRing-1].Link != "https://x/50" {
		t.Fatalf("ring: %d items, %s … %s", len(n.items), n.items[0].Link, n.items[len(n.items)-1].Link)
	}
}

// The poller runs only while someone asked within Idle: first request fetches, each tick refetches,
// and after Idle without requests the next tick ends polling with no fetch.
func TestNewsPollsOnlyWhileAsked(t *testing.T) {
	clk := newClock("2026-11-05T10:00:00+07:00")
	var mu sync.Mutex
	fetches := 0
	ticks := make(chan time.Time)
	n := &News{Feeds: DefaultFeeds, Fetch: fixtureFetch(t, "", &fetches, &mu), Now: clk.Now,
		After: func(time.Duration) <-chan time.Time { return ticks }}
	defer n.Close()
	count := func() int { mu.Lock(); defer mu.Unlock(); return fetches }
	if n.Polling() || count() != 0 {
		t.Fatal("nobody asked yet: no traffic")
	}
	items, sources := n.Items(context.Background())
	if count() != 3 || len(items) != 22 || !n.Polling() || len(sources) != 3 || !sources[0].OK {
		t.Fatalf("first ask: %d fetches, %d items, polling %v, sources %+v", count(), len(items), n.Polling(), sources)
	}
	clk.Add(NewsInterval)
	ticks <- clk.Now()
	waitFor(t, func() bool { return count() == 6 })
	clk.Add(5 * time.Minute)
	n.Items(context.Background()) // fetched 5 min ago: no fetch
	if count() != 6 {
		t.Fatalf("fresh ring refetched: %d", count())
	}
	clk.Add(NewsIdle + time.Minute)
	ticks <- clk.Now()
	waitFor(t, func() bool { return !n.Polling() })
	if count() != 6 {
		t.Fatalf("idle tick fetched: %d", count())
	}
	n.Items(context.Background()) // a new ask restarts polling with a fetch
	if count() != 9 || !n.Polling() {
		t.Fatalf("restart: %d fetches, polling %v", count(), n.Polling())
	}
}

func TestNewsSourceFailureIsReported(t *testing.T) {
	n := &News{Feeds: DefaultFeeds, Fetch: fixtureFetch(t, "kontan", nil, nil), Now: newClock("2026-11-05T10:00:00+07:00").Now,
		After: func(time.Duration) <-chan time.Time { return nil }}
	defer n.Close()
	items, sources := n.Items(context.Background())
	if len(items) != 14 {
		t.Fatalf("items = %d", len(items))
	}
	got := map[string]SourceStatus{}
	for _, s := range sources {
		got[s.Source] = s
	}
	if k := got["kontan"]; k.OK || str(k.Error) != "http 503" || k.FetchedAt != nil {
		t.Fatalf("kontan = %+v", k)
	}
	if c := got["cnbc"]; !c.OK || c.Error != nil || str(c.FetchedAt) != "2026-11-05T10:00:00+07:00" {
		t.Fatalf("cnbc = %+v", c)
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met in 5 s")
		}
		time.Sleep(5 * time.Millisecond)
	}
}
