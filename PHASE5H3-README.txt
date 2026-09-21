MarketLedger Pro V5H.3 — Extended-Hours Provider Routing

Purpose
- Fix the reproduced Monday pre-market condition where authenticated Alpaca IEX latest quote/bar endpoints return HTTP 200 but Friday-stale timestamps.
- Preserve V5H.1/V5H.2 freshness rejection; stale data is never relabeled fresh.

Routing
- REGULAR: existing Alpaca IEX behavior remains unchanged.
- PRE/POST/OVERNIGHT: try Alpaca first.
- If Alpaca is stale, query Yahoo 5-minute includePrePost history.
- Yahoo is accepted for the live/latest price layer only when its newest timestamp is <=20 minutes old.
- When Yahoo is used, stale Alpaca bid/ask and quote sizes are cleared rather than presented as current liquidity.
- If Yahoo is also stale/empty/unavailable, MarketLedger remains RESTORING DATA / WAIT.

Diagnostics
- Render logs now include `Market data route:` with Yahoo age and routing decision.
- Existing `Market data diag:` lines remain.

Unchanged
- BUY/STRONG thresholds
- PostgreSQL persistence
- Outcome Engine
- Corporate calendar
- Prior-session technical labeling
