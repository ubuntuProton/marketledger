MarketLedger Pro V5M.2 — Market-Moving Catalyst Ranking

Adds a second-stage significance filter after V5M.1 entity integrity.
- Classifies company-matched headlines as ACTIONABLE_CATALYST, SUPPORTING_CONTEXT, BACKGROUND, or IGNORE.
- Scores catalyst significance using source quality, entity relevance, freshness, and economic/business catalyst language.
- Penalizes tutorials, product reviews, generic prediction/promotional stories, and other low-decision-value content.
- Browser cross-checks high-significance news against MarketLedger's existing pre-open price/volume/VWAP/sector evidence.
- MARKET CONFIRMED is shown only when a high-significance catalyst also has meaningful tape corroboration.
- News still cannot create or upgrade BUY/STRONG technical setup status.
- No changes to technical scoring, thresholds, liquidity gates, outcome calibration, provider routing, or PostgreSQL schema.
