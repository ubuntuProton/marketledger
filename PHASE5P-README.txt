MarketLedger Pro V5P — True Overnight Data

Purpose
- Feed the existing V5N/V5N.1 overnight intelligence with actual Alpaca overnight-session data instead of treating missing Yahoo/IEX overnight candles as zero activity.

Routing
- 20:00–04:00 ET: existing Yahoo 5-day candles are merged with Alpaca overnight data.
- Alpaca feed=overnight supplies the latest overnight bar/indicative quote where the account is entitled.
- Alpaca feed=boats supplies historical overnight bars. On free/basic access these historical BOATS requests are delayed by at least 15 minutes.
- Missing intervals are never synthesized and missing volume is never interpreted as zero interest.
- Existing Yahoo/IEX fallbacks remain intact outside the overnight supplement.

Safety / integrity
- V5N.1 technical BUY/STRONG thresholds are unchanged.
- V5M.7.1 news classification is unchanged.
- V5O.2 persistent 58-stock monitoring-universe behavior is unchanged.
- Overnight latest price can be current while historical bars are delayed; the application keeps quote/candle freshness separate.

Future provider plug-in
- The data router remains provider-oriented so Schwab market data can be added after endpoint/entitlement verification without changing scoring rules.
