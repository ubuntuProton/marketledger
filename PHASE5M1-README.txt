MarketLedger Pro V5M.1 — News Relevance & Entity Integrity

Fixes false ticker/headline associations observed in the first V5M live test.
- Queries company aliases instead of bare ambiguous ticker strings where known.
- Requires explicit company/entity phrase matching for BE, COIN, ARM, META and other ambiguous symbols.
- Requires market/company context for ticker-only matches.
- Rejects common low-value SEO/investment-promo headline patterns.
- Deduplicates normalized headlines.
- Preserves source confidence, news-as-context rule, Opening Intelligence, and all technical thresholds.
