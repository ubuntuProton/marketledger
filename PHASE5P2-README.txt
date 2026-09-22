MarketLedger Pro V5P.2 — Premarket Data Continuity

Purpose
- Fix the V5P.1 gap observed live at 07:11 ET: quotes were fresh but many PRE 5-minute candles were ~900 minutes old.
- Preserve the existing V5N.1 evidence/risk thresholds. This patch changes data continuity, not scoring.

Changes
1. New authenticated endpoint: GET /api/session-bars/{symbol}
   - PRE/REGULAR/POST: requests Alpaca 5-minute IEX history plus latest IEX bar.
   - Returns Yahoo-compatible candle JSON so the existing analysis engine needs no scoring rewrite.
2. Browser fetchCandles() now merges authenticated session bars during PRE/open/intraday/close/post phases.
3. Existing /api/overnight-bars remains authoritative during OVERNIGHT.
4. Alpaca bar parsing is now field-order tolerant instead of depending on provider JSON key order.
5. Missing authenticated bars are not synthesized. Existing Yahoo/includePrePost data remains the fallback/base.

Expected validation
- At 07:00 ET, cards should no longer show ~900-minute candle age when Alpaca IEX has current premarket trades for that symbol.
- Thin symbols may legitimately have older candles; the engine should continue to WAIT rather than invent activity.
- PRE Evidence Fusion should begin differentiating candidates once >=3 current extended-hours 5-minute candles exist.

Unchanged
- BUY/STRONG thresholds
- V5N.1 Evidence Fusion scoring
- V5M.7.1 news classification
- V5O.2 persistent 58-stock universe
- overnight BOATS/latest logic
