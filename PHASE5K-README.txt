MarketLedger Pro V5K — Fast Opening Setup Engine

Adds a separate 9:30–10:30 ET opening engine instead of forcing every positive opening structure to WAIT.

Opening labels:
- STRONG OPEN SETUP
- OPEN SETUP
- WATCH OPEN
- WAIT & SEE
- AVOID / NOT NOW

Opening engine requires current-session completed 5-minute candles and evaluates opening VWAP, EMA9/EMA21, 15-minute momentum, opening volume baseline, repeated VWAP closes, market/sector confirmation, event risk, and chase risk.

Safety / continuity:
- Requires at least 2 completed opening candles before an opening setup is eligible.
- High-impact events downgrade positive opening setups.
- Existing stale-data, spread/liquidity, market/sector, and event gates remain authoritative.
- Existing PRE/EXT engine retained.
- Existing post-10:30 intraday engine retained.
- V5J.3 PostgreSQL duplicate-safe startup retained.
- No database/schema reset.
