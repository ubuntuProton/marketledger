MarketLedger Pro V5H.1 — Cloud Restart & Freshness Recovery

Fixes the Render cold-start condition reproduced on 2026-09-21:
- PostgreSQL/watchlist survives restart, but Alpaca can return old quote/bar objects.
- A returned quote object is no longer counted as a LIVE quote unless its timestamp is fresh.
- Stale Alpaca quote/bar values cannot replace the displayed live/latest price.
- Stale minute volume cannot be labeled "Ext live 1m".
- PRE market technical indicators are explicitly labeled Prior-session technicals.
- When no genuinely fresh provider timestamp exists, the board shows RESTORING DATA.
- Provider health now reports fresh quotes separately from candle responses.
- Refresh quotes now runs the real market-data analysis/hydration path instead of the legacy Stooq-only refresh.
- Cold page load announces restoring live data while the first automatic hydration runs.

Freshness limits:
- Regular session: 12 minutes.
- Extended sessions: 20 minutes.

Safety:
- BUY/STRONG thresholds unchanged.
- PostgreSQL persistence unchanged.
- V5H outcome engine unchanged.
- V5G.7 corporate calendar unchanged.
