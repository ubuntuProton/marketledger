MarketLedger Pro Cloud V4 — Windows → Cloud Migration

Purpose
- Keeps Neon PostgreSQL persistence from V3.
- Adds a merge-based Windows data importer in Cloud Data Safety.
- Accepts a ZIP containing stocks.tsv, calls.tsv, notes.tsv, events.tsv, and signals.tsv.
- settings.properties and credentials are intentionally ignored.
- Existing cloud data is preserved.
- Tickers are deduplicated by symbol.
- Signals are deduplicated by ticker + candle timestamp.
- Calls, notes, and events are deduplicated by record content and receive safe cloud IDs.
- The same migration ZIP can be imported again without duplicating the same records.

Migration
1. Deploy V4.
2. Open Cloud Data Safety.
3. Choose the ZIP of the Windows MarketLedgerPro folder.
4. Click Import Windows data.
5. Confirm the import counts.
6. Verify the watchlist and history.
7. Redeploy once and verify the migrated data remains.

Secrets
- settings.properties is not imported.
- Keep ALPACA_API_KEY, ALPACA_API_SECRET, MARKETLEDGER_PASSWORD and DATABASE_URL in Render environment variables.
