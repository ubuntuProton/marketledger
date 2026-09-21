MarketLedger Pro V5K.2 — Authoritative Gate Attribution

Changes:
- Distinguishes Limiting gate, Confirmation, and Context in Top Setup Queue and board/mobile cards.
- Prioritizes status-changing negative gates over later positive context messages.
- Chase/extension explanations include numeric distance above VWAP and move from session open.
- Example: a high technical score held by chase protection now reports the chase gate instead of generic market/sector confirmation.
- No signal thresholds, database schema, provider routing, outcome rules, or calendar rules changed.
- Retains V5K opening engine, V5K.1 provider failure diagnostics, V5J.2 fresh-liquidity protection, and V5J.3 PostgreSQL startup safety.
