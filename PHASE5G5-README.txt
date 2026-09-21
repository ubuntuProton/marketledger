MarketLedger Pro V5G.5 — Earnings Status / Timing / Source

- Adds explicit earnings metadata API for cached and freshly synced events.
- Upcoming Earnings table gains STATUS and SOURCE fields.
- CONFIRMED, ESTIMATED, and legacy CACHED states are distinguished.
- Timing is carried separately; confirmed MU is AMC with a 4:30 PM ET confirmed-event timestamp.
- Source provenance is displayed when available (e.g. Micron Investor Relations, Xoomar/SEC).
- Cached events remain visible during provider outages.
- Xoomar circuit breaker/backoff from V5G.4 is retained.
- Corporate calendar diagnostics remain separate by provider.
- No new API key or Render secret required.
