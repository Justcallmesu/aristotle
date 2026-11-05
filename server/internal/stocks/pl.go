package stocks

import (
	"math"
	"math/big"
)

// P/L after broker fees (spec Q88, protocol §20), in integer IDR:
//
//	shares = lots × LotSize
//	cost   = avg_price × shares × (1 + buy_fee_pct/100)
//	value  = price × shares × (1 − sell_fee_pct/100)
//	pl     = value − cost;  pl_pct = pl / cost × 100, 2 decimals
//
// Each amount is rounded half up once, from the exact product (math/big, no float error).
// Fees count to 0.0001 % (ppm of the amount); avg_price to 0.01 IDR.

const ppm = 1_000_000

// feePPM turns a percent fee into parts per million of the amount (0.15 % → 1500).
func feePPM(pct float64) int64 { return int64(math.Round(pct * 10_000)) }

// centi turns an IDR price into hundredths of IDR.
func centi(price float64) int64 { return int64(math.Round(price * 100)) }

// Cost is what buying lots at avg cost with the buy fee.
func Cost(avg float64, lots int64, buyFeePct float64) int64 {
	num := mul(centi(avg), lots*LotSize, ppm+feePPM(buyFeePct))
	return roundDiv(num, big.NewInt(100*ppm))
}

// Value is what selling lots at price brings after the sell fee.
func Value(price, lots int64, sellFeePct float64) int64 {
	num := mul(price, lots*LotSize, ppm-feePPM(sellFeePct))
	return roundDiv(num, big.NewInt(ppm))
}

// Pct is part/whole × 100 rounded half away from zero to 2 decimals; whole must be > 0.
func Pct(part, whole int64) float64 {
	hundredths := roundDiv(mul(part, 10_000, 1), big.NewInt(whole))
	return float64(hundredths) / 100
}

func mul(a, b, c int64) *big.Int {
	n := big.NewInt(a)
	n.Mul(n, big.NewInt(b))
	return n.Mul(n, big.NewInt(c))
}

// roundDiv is num/den (den > 0) rounded half away from zero.
func roundDiv(num, den *big.Int) int64 {
	neg := num.Sign() < 0
	n := new(big.Int).Abs(num)
	n.Mul(n, big.NewInt(2)).Add(n, den)
	n.Quo(n, new(big.Int).Mul(den, big.NewInt(2)))
	if neg {
		n.Neg(n)
	}
	return n.Int64()
}

// Position is one holding's P/L; the price-dependent fields are nil without a price.
type Position struct {
	Cost  int64
	Value *int64
	PL    *int64
	PLPct *float64
}

// Evaluate computes a holding's P/L at price (nil: unknown).
func Evaluate(h Holding, price *int64, set Settings) Position {
	p := Position{Cost: Cost(h.AvgPrice, h.Lots, set.BuyFeePct)}
	if price == nil {
		return p
	}
	v := Value(*price, h.Lots, set.SellFeePct)
	pl := v - p.Cost
	pct := 0.0
	if p.Cost > 0 {
		pct = Pct(pl, p.Cost)
	}
	p.Value, p.PL, p.PLPct = &v, &pl, &pct
	return p
}

// Total sums positions; value, pl and pl_pct are nil when any position has no price.
func Total(ps []Position) Position {
	t := Position{}
	for _, p := range ps {
		t.Cost += p.Cost
	}
	var value int64
	for _, p := range ps {
		if p.Value == nil {
			return t
		}
		value += *p.Value
	}
	pl, pct := value-t.Cost, 0.0
	if t.Cost > 0 {
		pct = Pct(pl, t.Cost)
	}
	t.Value, t.PL, t.PLPct = &value, &pl, &pct
	return t
}
