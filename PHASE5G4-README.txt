MarketLedger Pro V5G.4 — Earnings Metadata + Provider Backoff

Earnings semantics:
- Normalizes event metadata into confidence, timing, source, and impact semantics.
- Company-confirmed dates override estimates.
- MU remains confirmed for 2026-09-30 with AMC timing.
- HIGH event risk is reserved for confirmed events in the near-event window.
- Estimated dates are retained as calendar context rather than treated as equally authoritative.

Provider resilience:
- Xoomar per-ticker requests now have a circuit breaker.
- After 3 consecutive provider/transport failures, MarketLedger stops the remaining ticker requests.
- Xoomar is backed off for 30 minutes instead of repeatedly issuing 29 failing requests.
- PostgreSQL cached events remain available during provider outages.
- Diagnostics report when the circuit is open and the approximate retry interval.

Existing V5D through V5G.3 functionality is retained.
