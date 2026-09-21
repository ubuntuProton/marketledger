MarketLedger Pro V5M — News & Catalyst Intelligence

Adds a Morning News & Catalyst Intelligence panel that correlates fresh public headlines with MarketLedger's current candidate set.

Key behavior:
- Public headline discovery for top MarketLedger candidates, cached for 5 minutes.
- Ticker extraction, freshness, theme clustering, and source-confidence display.
- Reuters/Schwab and other higher-authority sources receive higher source-confidence labels when surfaced by the public news index.
- News is context only: it never creates BUY/STRONG by itself. Existing price/volume/liquidity/event gates remain authoritative.
- Existing Schwab Week Ahead calendar sync remains unchanged.
- Reuters/LSEG professional machine-readable news is NOT scraped or bundled; licensed feeds require separate entitlement.
- No database migration and no scoring-threshold changes.

Deploy by replacing the current repository files and pushing to main. Render rebuilds from Dockerfile.
