MarketLedger Pro V5E.1 — Earnings Ingestion Reliability

Fixes:
- Yahoo quoteSummary now uses the cookie + crumb session required by the current endpoint.
- Handles both raw epoch and ISO date-time earningsDate response shapes.
- Reads Yahoo's isEarningsDateEstimate flag.
- Does NOT infer BMO/AMC from a generic timestamp. Auto-synced Yahoo events are labeled Time TBD.
- Manual events can still explicitly say Before market open / After market close.
- Sync diagnostics report: watchlist checked, dates found, changes, failures.
- Provider failures are visible instead of silently looking like "0 upcoming earnings".
- Existing duplicate Context/Event rows are deduplicated during earnings sync.
- Search horizon expanded to 60 days.
- Existing V5D outcome engine, V5D.1 dual freshness, V5D.2 prior-session technical context,
  and V5E earnings risk/notification behavior are retained.

After deployment:
Click "Sync earnings now". A healthy result should show nonzero "dates found" when the provider has
calendar dates for watchlist companies. If failures are nonzero, the UI now surfaces the first few
provider errors for diagnosis.
