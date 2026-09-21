MarketLedger Pro V5G.7 — Automated Earnings Verification / Reconciliation

Verification rules
- CONFIRMED is reserved for authoritative company/Investor Relations dates.
- Two independent calendar providers agreeing on the same date become CORROBORATED.
- CORROBORATED is intentionally NOT promoted to CONFIRMED.
- A weaker provider can never overwrite an existing company-confirmed date.
- Conflicting provider dates are recorded in verification diagnostics instead of silently replacing an authoritative date.
- BMO/AMC is retained only when supplied by an authoritative verified record; otherwise timing remains TBD.

Endpoints
- GET /api/context/earnings/verification exposes symbol, status, selected date, timing, source, and reconciliation note.

MU regression case
- 2026-09-30
- AMC
- CONFIRMED
- Micron Investor Relations
- Provider disagreements cannot overwrite it.

Existing behavior retained
- PostgreSQL/cache preservation
- Xoomar circuit breaker/backoff
- Alpha Vantage merge
- IPO calendar
- V5D outcome engine and technical engine
