package stocks

import "testing"

func TestCostValuePct(t *testing.T) {
	tests := []struct {
		name            string
		avg             float64
		price, lots     int64
		buy, sell       float64
		cost, value, pl int64
		pct             float64
	}{
		{"contract example §20", 4800, 4930, 10, 0.15, 0.25, 4807200, 4917675, 110475, 2.3},
		{"no fees, loss", 1000, 900, 1, 0, 0, 100000, 90000, -10000, -10},
		{"fractional avg, cost rounds up", 4812.5, 4930, 1, 0.15, 0.25, 481972, 491768, 9796, 2.03},
		{"cost exactly half rounds up", 1, 1, 1, 0.5, 0, 101, 100, -1, -0.99},
		{"value exactly half rounds up", 1, 2, 1, 0, 0.25, 100, 200, 100, 100},
		{"max lots stays exact", 9_999_999.99, 9_999_999, MaxLots, 5, 5, 10_499_999_989_500_000, 9_499_999_050_000_000, -1_000_000_939_500_000, -9.52},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			h := Holding{Code: "BBCA", Lots: tt.lots, AvgPrice: tt.avg}
			p := Evaluate(h, &tt.price, Settings{BuyFeePct: tt.buy, SellFeePct: tt.sell})
			if p.Cost != tt.cost || *p.Value != tt.value || *p.PL != tt.pl || *p.PLPct != tt.pct {
				t.Fatalf("got cost %d value %d pl %d pct %v, want %d %d %d %v", p.Cost, *p.Value, *p.PL, *p.PLPct, tt.cost, tt.value, tt.pl, tt.pct)
			}
		})
	}
}

func TestPctRoundsHalfAwayFromZero(t *testing.T) {
	tests := []struct {
		part, whole int64
		want        float64
	}{
		{1, 800, 0.13}, {-1, 800, -0.13}, {1, 3, 33.33}, {2, 3, 66.67}, {-2, 3, -66.67}, {0, 5, 0}, {90, 4840, 1.86},
	}
	for _, tt := range tests {
		if got := Pct(tt.part, tt.whole); got != tt.want {
			t.Errorf("Pct(%d, %d) = %v, want %v", tt.part, tt.whole, got, tt.want)
		}
	}
}

func TestEvaluateWithoutPriceAndTotals(t *testing.T) {
	set := Settings{BuyFeePct: 0.15, SellFeePct: 0.25}
	a := Evaluate(Holding{Lots: 10, AvgPrice: 4800}, i64(4930), set)
	b := Evaluate(Holding{Lots: 2, AvgPrice: 3000}, nil, set)
	if b.Cost != 600900 || b.Value != nil || b.PL != nil || b.PLPct != nil {
		t.Fatalf("no price = %+v", b)
	}
	if tot := Total([]Position{a, b}); tot.Cost != 4807200+600900 || tot.Value != nil || tot.PL != nil {
		t.Fatalf("partial total = %+v", tot)
	}
	c := Evaluate(Holding{Lots: 2, AvgPrice: 3000}, i64(3100), set)
	tot := Total([]Position{a, c})
	wantValue := int64(4917675 + 618450)
	if tot.Cost != 5408100 || *tot.Value != wantValue || *tot.PL != wantValue-5408100 || *tot.PLPct != Pct(wantValue-5408100, 5408100) {
		t.Fatalf("total = cost %d value %d pl %d pct %v", tot.Cost, *tot.Value, *tot.PL, *tot.PLPct)
	}
	if empty := Total(nil); empty.Cost != 0 || *empty.Value != 0 || *empty.PL != 0 || *empty.PLPct != 0 {
		t.Fatalf("empty total = %+v", empty)
	}
}
