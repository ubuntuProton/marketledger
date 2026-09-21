MarketLedger Pro V5G.6 — Earnings Metadata Migration

- Migrates legacy cached EARNINGS records to explicit metadata.
- Company-confirmed metadata overrides legacy records even when the calendar date already matches.
- MU becomes 2026-09-30 16:30 ET / AMC / CONFIRMED / Micron Investor Relations.
- Legacy unverified earnings become ESTIMATED / TBD with source provenance where recoverable.
- Upcoming Earnings table is normalized to:
  TICKER | WHEN | TIMING | STATUS | SOURCE | RISK
- PostgreSQL/cache compatibility retained.
- Xoomar circuit breaker/backoff retained.
- IPO subsystem intentionally unchanged.
- No new API key or Render secret required.
