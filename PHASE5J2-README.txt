MarketLedger Pro V5J.2 — Fresh Liquidity Gate

V5J.1 proved the Extended-Hours engine is working, but exposed a gating mismatch:
fresh Yahoo extended-hours candles could produce EXT-HOURS SETUP/STRONG while an old
Alpaca IEX bid/ask from the prior session could still downgrade the final status.

V5J.2:
- applies extended-hours spread and displayed-size gates only when the bid/ask quote itself is <=20 minutes old
- stale bid/ask is ignored rather than downgrading a fresh extended-hours candle setup
- records 'stale bid/ask ignored for extended-hours liquidity gate' in Analyze reasons
- keeps event gates, market/sector confirmation, gap/chase protection and all regular-session thresholds
- retains V5J Extended-Hours / Overnight engine and V5J.1 startup hotfix
- no database/schema/persistence changes
