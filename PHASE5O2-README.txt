MarketLedger Pro V5O.2 - Persistent Expanded Monitoring Universe

- Fixes the 31-watchlist / 64-news-universe mismatch.
- Approved V5O monitoring candidates are merged server-side into the persistent watchlist at startup.
- Existing symbols are preserved and duplicates are skipped.
- PostgreSQL/blob persistence is updated through the existing atomic watchlist write path.
- Adds POST /api/stocks/v5o-universe for repeat-safe manual reconciliation.
- The dashboard button now uses the batch server endpoint instead of dozens of browser POST requests.
- Sector grouping and Live/5m freshness from V5O.1 are retained.
- V5N.1 technical thresholds and V5M.7.1 catalyst classifier are unchanged.
