MarketLedger Pro V5K.6 — Quote Quality Guard + Fallback Reason Integrity

Changes:
- Rejects fresh Alpaca quote midpoint as displayed live price when bid/ask spread exceeds the existing liquidity threshold (0.40% regular, 0.60% extended).
- Wide bid/ask remains available and continues to drive liquidity risk gates.
- A fresh Alpaca bar / Yahoo candle remains eligible as the displayed live price.
- Adds live_price_quality (GOOD/DEGRADED) to Live Board CSV and Analyze.
- Non-positive fallback reason attribution prefers adverse technical evidence instead of a positive observation.
- No scoring, signal thresholds, liquidity thresholds, event rules, provider routing, database schema, or outcome logic changed.
