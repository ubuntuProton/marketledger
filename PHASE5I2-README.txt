MarketLedger Pro V5I.2 — Analyze Repair + Visible Pre-Market Diagnostics

Fixes:
- Analyze now opens the existing detail/chart immediately when a ticker is already analyzed, then refreshes that ticker.
- Analyze scrolls the detail box into view after opening/refreshed rendering.
- Analyze failures are logged to the browser console and surfaced by toast.
- Adds a PM Engine column directly to the desktop board during PRE, so eligibility no longer depends on opening Analyze.
- Detail panel shows PM score/label or NOT ELIGIBLE, current PM bar count, total candle count, newest candle ET time, PM VWAP, 30m momentum and reason.
- PM positive states are included in existing extended-hours spread/liquidity gates.

No changes:
- V5H.4 provider routing/freshness protections.
- Regular-session technical thresholds.
- PostgreSQL persistence.
- Outcome Engine.
- Corporate calendar.
