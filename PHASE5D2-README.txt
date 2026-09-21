MarketLedger Pro V5D.2 — Prior-Session Technical Context

Purpose:
Prevent multi-day prior-session candles from being presented as if they were current overnight technical candles.

Changes:
- Sunday/overnight technical candles older than 180 minutes are classified as Prior-session technicals.
- Data Freshness continues to show the independent live quote age.
- Instead of "Candle 3264m", the board shows "Technical: Prior session".
- Technical Score displays its context: Prior-session technicals or Current-session technicals.
- Analyze panel shows the actual technical-candle timestamp while labeling it Prior session.
- No fake/synthetic 5-minute candles are created from quote snapshots.
- No BUY/STRONG thresholds were changed.
- V5D PostgreSQL outcome engine remains unchanged.
