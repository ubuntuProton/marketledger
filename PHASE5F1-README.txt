MarketLedger Pro V5F.1 — Provider Diagnostics
- Valid Alpha Vantage earnings CSV with zero watchlist matches is treated as success.
- Yahoo fallback only runs when Alpha Vantage actually fails.
- Adds safe provider diagnostics: HTTP status, content type, CSV header, provider rows, parsed rows, matches.
- Adds diagnostics for both earnings and IPO feeds.
- Never exposes API keys or full provider payloads.
- Existing V5D through V5F functionality retained.
