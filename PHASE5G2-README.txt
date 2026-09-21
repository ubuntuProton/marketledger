MarketLedger Pro V5G.2 — Provider Provenance + IPO Reliability

- Keeps separate diagnostics for Xoomar, Alpha Vantage earnings, Nasdaq IPO, and Alpha Vantage IPO.
- Fixes last-provider-wins diagnostics that hid the successful Xoomar earnings result.
- Dashboard now reports Xoomar earnings coverage separately from Alpha Vantage merge coverage.
- Dashboard reports Nasdaq IPO parsing separately from Alpha Vantage.
- Adds an independent no-key nfin/Nasdaq IPO merge source.
- Existing cached corporate events are preserved.
- Existing 25-date earnings dataset is not removed by an empty provider response.
- No new API key or Render secret is required.
- Existing V5D through V5G.1 functionality retained.
