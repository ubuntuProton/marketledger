MarketLedger Pro V5E.2 — Cloud-Safe Earnings Provider

Primary provider:
- Alpha Vantage EARNINGS_CALENDAR, fetched as one 3-month CSV request and filtered to the watchlist.
- Render secret environment variable: ALPHA_VANTAGE_API_KEY
- The API key is server-side only and is never returned to the browser or stored in PostgreSQL.

Reliability:
- Yahoo is fallback only.
- Existing cached earnings events are preserved if providers fail or rate-limit.
- Sync diagnostics explicitly show whether Alpha Vantage is configured.
- Existing Context/Event deduplication retained.
- Earnings timing remains TBD because the calendar date feed does not establish BMO/AMC.
- No directional points are added by earnings.
- Existing V5D through V5E.1 functionality retained.

Render setup:
Add ALPHA_VANTAGE_API_KEY as a secret environment variable, redeploy, then click Sync earnings now.
