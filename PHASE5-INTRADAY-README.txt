MarketLedger Pro Cloud V5 — Phase 5 Intraday Reliability

Changes from validated V4 baseline:
- Preserves Neon PostgreSQL persistence and Windows migration.
- Technical score is explicitly separated from Final Status; score is not a probability.
- Session-aware stale-data gate: 12 minutes during active regular-session phases, 20 minutes in extended hours.
- A stale feed cannot produce BUY/STRONG; final status is forced to WAIT unless already AVOID.
- Closed-market freshness is shown as Prior session/HISTORICAL instead of thousands of minutes stale.
- Detail panel shows Data Quality (LIVE/AGING/STALE/HISTORICAL).
- Tracks status transitions during the running browser session and shows the latest transition in Analyze detail.
- Ranking remains Final Status first; risk/event/time/liquidity gates remain authoritative.
- Signal Memory remains persistent through PostgreSQL.
- render.yaml no longer declares a Render-owned database; DATABASE_URL is a secret supplied manually (Neon).

Deploy by replacing the current repository files, committing, and pushing main.
Do not remove the DATABASE_URL already saved in Render.
