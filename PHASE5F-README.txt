MarketLedger Pro V5F — Unified Corporate Calendar

Adds:
- Upcoming IPOs dashboard panel.
- Alpha Vantage IPO_CALENDAR ingestion.
- Unified corporate-calendar sync for earnings + IPOs every 6 hours.
- IPO browser/PWA notifications for events within 7 days while the app/PWA is active.
- Earnings notifications retained.
- IPO symbol, company, expected date, and price range when the provider supplies them.
- Cached IPO and earnings events persist through provider outages.
- Provider-response diagnostics distinguish empty/invalid/JSON-notice responses from valid CSV.
- Alpha Vantage remains server-side via ALPHA_VANTAGE_API_KEY; the key is never returned to the browser.
- Yahoo remains earnings fallback only.
- Existing technical engine, V5D outcomes, dual freshness, prior-session labeling, and event deduplication retained.

Important:
Expected IPO dates and ranges can change. IPO alerts are calendar notifications, not trading recommendations.
Browser/PWA notifications fire when the app is active and notification permission has been granted.
