MarketLedger Pro V5M.5 — Materiality & Evidence Integrity

Adds independent catalyst dimensions:
- Novelty: NEW_CATALYST / FOLLOW_THROUGH / COMMENTARY / BACKGROUND
- Evidence: CONFIRMED / REPORTED / ANALYST / SPECULATIVE
- Materiality: HIGH / MEDIUM / LOW
- Exposure: DIRECT / INDIRECT

Integrity rules:
- LOW materiality and SPECULATIVE stories cannot become ACTIONABLE_CATALYST.
- Analyst actions can be supporting context but do not become confirmed corporate catalysts.
- Options/open-interest stories are market-flow observations, not corporate catalysts.
- Administrative settlement-claims updates are low materiality.
- Indirect events (for example a backed portfolio company) are penalized.
- HIGH + NEW + non-speculative evidence is required for ACTIONABLE_CATALYST.
- Technical BUY/STRONG thresholds are unchanged. News never creates a technical BUY by itself.
