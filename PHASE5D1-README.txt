MarketLedger Pro V5D.1 — Dual Freshness Model
- Separates live Alpaca quote age from 5-minute candle age.
- Fixes prior barTs-first timestamp selection.
- Current extended-hours price uses fresher bid/ask midpoint when quote is newer than latest bar.
- Completed candles remain authoritative for technical indicators and stale-candle gating.
- Analyze view shows quote and candle timestamps/ages separately.
- V5D PostgreSQL outcome engine remains unchanged.
