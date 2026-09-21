MarketLedger Pro V5L.2 - Analysis Pipeline Hotfix

Fixes
- PostgreSQL outcome worker now binds java.time.Instant as java.sql.Timestamp for TIMESTAMPTZ columns.
- High-frequency signal capture no longer rebuilds every native PostgreSQL table for every analyzed ticker.
- Each signal capture upserts only its current signal_observations/signal_outcomes rows while preserving the TSV/blob compatibility backup.
- Real-time Setup Board now shows per-ticker analysis progress and a visible analysis error instead of remaining indefinitely at "Waiting for analysis".

Unchanged
- V5L.1 Opening Intelligence scoring, discovery universe, sector breadth, fixed pre-open snapshots and opening handoff.
- Regular-session STRONG/BUY thresholds and quote-quality gates.
- PostgreSQL schema; no migration required.
