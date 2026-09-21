MarketLedger Pro V5J.3 — PostgreSQL Startup Sync Safety

Fixes the Render startup failure:
duplicate key value violates unique constraint watchlist_pkey (NVDA)

Changes:
- watchlist native-table synchronization is now idempotent with PostgreSQL ON CONFLICT(symbol) DO UPDATE
- stocks.tsv is de-duplicated by normalized ticker symbol when read, so duplicate legacy/blob rows cannot crash startup
- malformed short stock rows are skipped safely
- V5J.2 fresh extended-hours liquidity gate is retained
- no schema changes
- no watchlist deletion or database reset required
