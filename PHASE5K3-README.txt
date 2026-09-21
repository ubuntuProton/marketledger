MarketLedger Pro V5K.3 — Session Reference Integrity

Purpose
- Fix regular-session calculations so current-day metrics never mix same weekday candles from prior dates.
- Make 09:30 ET the authoritative regular-session open reference.
- Use the prior regular trading-session close for Vs Reg Close and extended-hours reference calculations.
- Separate a non-blocking caution (for example RSI overextended) from a true limiting gate.

Fixes
1. sessionCandles() now filters by exact New York YYYY-MM-DD, not day-of-week name.
   This prevents Monday candles from previous Mondays in the 5-day response from contaminating VWAP, EMA, RSI, momentum, ATR and from-open calculations.
2. From-open now derives from the first 09:30 regular-session candle of the latest regular trading date.
3. Added previousRegularClose() and uses it for Regular close reference / Vs regular close and PRE/EXT reference calculations.
4. Positive BUY/STRONG states can show Caution: RSI overextended rather than incorrectly calling it a Limiting gate when it did not block the setup.
5. Detail label now explicitly says From 09:30 open.

Unchanged
- BUY / STRONG thresholds
- chase threshold
- event gates
- fresh-liquidity gate
- V5K opening engine thresholds
- PostgreSQL schema/persistence
- Signal Memory/outcome measurement rules
- provider routing

Validation
- Browser inline JavaScript: node --check PASS
- Java: javac --release 17 PASS
