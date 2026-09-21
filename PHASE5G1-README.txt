MarketLedger Pro V5G.1 — Multi-Source Corporate Calendar Merge

Earnings:
- Xoomar is queried per watchlist ticker using its documented /api/markets/earnings/{ticker} endpoint.
- Uses each ticker's data.next record instead of relying on a sparse global calendar response.
- Alpha Vantage results are merged when configured rather than used only after an empty primary result.
- Duplicate ticker dates are suppressed; SEC-derived Xoomar dates take precedence.
- Yahoo is attempted only if all primary/merge providers actually fail.

IPOs:
- Nasdaq parser now targets data.upcoming.rows instead of recursively treating metadata as rows.
- Supports expectedPriceDate and expectedDate date fields.
- Alpha Vantage IPO results are merged when configured.
- Duplicate symbol/date entries are suppressed.

Reliability:
- Cached events remain preserved.
- Provider diagnostics remain enabled.
- No new API key required.
- Existing V5D through V5G behavior retained.
