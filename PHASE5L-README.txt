MarketLedger Pro V5L - Opening Intelligence Engine

Purpose
- Adds a separate pre-open opportunity-discovery layer before 09:30 ET.
- Does not weaken or replace the existing confirmation/risk engine.

Opening Intelligence Radar (04:00-09:29 ET)
- Premarket relative volume versus historical same-session elapsed-time baseline.
- Recent 30-minute volume acceleration versus the preceding 30 minutes.
- Premarket VWAP position and persistence.
- 30-minute premarket momentum and proximity to premarket high.
- Gap versus previous regular close with chase-risk penalties.
- SPY/QQQ/sector context.
- Existing event and spread/liquidity risk controls.
- Coverage confidence indicates whether historical/overnight evidence is complete enough.

Labels
- EARLY LEADER
- PRE-OPEN SETUP
- WATCH AT OPEN
- NO PRE-OPEN EDGE

Auditability
- Analyze includes Opening Intelligence details.
- Live Board CSV includes preopen label, score, confidence, relative volume,
  volume acceleration, momentum, gap, and reason fields.

Important
- Opportunity scores are evidence/ranking scores, not probabilities or guarantees.
- No change to regular-session technical scoring, BUY/STRONG thresholds,
  quote-quality guard, database schema, or outcome engine.
