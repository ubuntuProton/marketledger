MarketLedger Pro V5P.1 — Overnight Session Integration

Fixes the 20:00-04:00 ET session-date boundary in the Extended-Hours engine.
At 20:00-23:59 ET, overnight bars are now classified from the current NY date; after midnight and through premarket, the session correctly spans the prior NY date 20:00-23:59 plus current date 00:00-03:59.

This allows Alpaca overnight bars already ingested by V5P to populate ON bar counts and the overnight VWAP/EMA/momentum/volume engine.
No V5N.1 technical thresholds, V5M.7.1 news classification, V5O.2 persistent universe, or quote-quality gates were changed.
