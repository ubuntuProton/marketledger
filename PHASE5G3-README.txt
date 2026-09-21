MarketLedger Pro V5G.3 — Earnings Date Verification

Verification layer:
- Adds an authoritative confirmed-date override layer above estimated calendar feeds.
- MU verification case is sourced from Micron Investor Relations:
  September 30, 2026, conference call at 2:30 PM Mountain / 4:30 PM Eastern.
- MU's prior September 22 estimate is replaced by September 30 and marked confirmed.
- Confirmed dates take precedence over Xoomar/SEC cadence estimates.
- Estimated dates remain useful calendar context but are not treated as equivalent to company-confirmed dates.
- Confirmed events remain eligible for HIGH event-risk gating near the event.
- Estimated events are downgraded to MEDIUM context until verified.
- Existing multi-source diagnostics, PostgreSQL persistence, and cached events are retained.

Architecture:
Company-confirmed / IR date
          ↓ highest authority
Broker / reliable scheduled calendar
          ↓
SEC-cadence estimate
          ↓ lowest authority
Final MarketLedger event record

This release establishes the verification layer. Future confirmed dates can be added by automated IR/provider verification rather than ticker-specific scoring changes.
