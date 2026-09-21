MarketLedger Pro V5I — Pre-Market Setup Engine

Adds a separate PRE-market decision-support engine. It does not unlock the regular-session BUY engine.

Eligibility
- PRE phase only (04:00–09:30 ET).
- Requires at least 3 current-day pre-market 5-minute candles.
- Positive PM labels cannot be produced from a current quote plus prior-session technicals alone.

Inputs
- Current pre-market 5-minute candles.
- Pre-market VWAP.
- PM EMA9/EMA21.
- ~30-minute PM momentum.
- Three closes above PM VWAP.
- Current PM cumulative volume.
- Same-ticker historical PM volume baseline from available 5-day candle history, normalized to current candle count.
- Price vs previous regular close and gap/chase protection.
- SPY/QQQ/sector confirmation.
- Existing event gate.
- Existing liquidity/spread gate when reliable bid/ask exists.

Labels
- STRONG PRE-MARKET SETUP
- PRE-MARKET SETUP
- WATCH PRE-MARKET
- WAIT & SEE
- AVOID / NOT NOW

At 09:30 ET the PM labels expire automatically and the existing opening no-chase/regular-session engine resumes authority.

This is technical decision support, not a guarantee or automatic trade instruction.
