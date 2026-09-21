MarketLedger Pro V5J — Extended-Hours / Overnight Setup Engine

Adds a separate extended-hours setup engine for OVERNIGHT and PRE phases.

Evidence used when actually available from the candle provider:
- current overnight or pre-market 5-minute candles
- extended-hours VWAP
- EMA9 / EMA21 structure
- ~30-minute extended-hours momentum
- 3 closes above extended VWAP
- elapsed-session volume versus same-session historical baseline
- price gap versus previous regular close / chase protection
- SPY / QQQ / sector confirmation
- existing event and liquidity/spread gates

Labels:
- STRONG EXT-HOURS SETUP
- EXT-HOURS SETUP
- WATCH EXT-HOURS
- WAIT & SEE
- AVOID / NOT NOW

Important data-integrity behavior:
- Overnight evidence is never invented. If the provider supplies zero overnight bars, the detail panel explicitly reports ON 0 and the engine does not award overnight confirmation.
- Pre-market volume classification is corrected to start at 04:00 ET (V5I used 07:00 in the summary calculation).
- Live source timestamp now comes from the exact live price source timestamp used by the engine, preventing a fresh age from being displayed beside an unrelated stale Alpaca timestamp.

Regular-session BUY/STRONG thresholds are unchanged.
PostgreSQL, Outcome Engine, corporate calendar and V5H.4 provider routing are retained.
