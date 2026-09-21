MarketLedger Pro V5H — Outcome Engine Validation

Purpose
- Return focus to the V5D empirical outcome engine.
- Do NOT change BUY/STRONG/HOLD/WAIT thresholds.
- Surface whether +15m, +30m, +60m, MFE, and MAE measurements are actually being populated.

New API
GET /api/outcomes/status

Returns:
- total signal_outcomes rows
- measured +15m count
- measured +30m count
- measured +60m count
- measured MFE count
- measured MAE count
- engine state

Dashboard
- Adds Outcome Engine Validation card.
- Calibration remains explicitly locked when mature samples are insufficient.

Validation target during a regular session
1. Fresh current-session observations appear.
2. +15m begins increasing after signals mature.
3. +30m follows.
4. +60m, MFE and MAE populate after the full horizon.
5. Only after enough mature samples exist should calibration statistics be reviewed.

Corporate-calendar functionality from V5G.7 is retained.
