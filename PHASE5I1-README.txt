MarketLedger Pro V5I.1 — Pre-Market Engine + Eligibility Diagnostics
Corrects V5I packaging and adds the actual PRE engine to the deployed UI.
Requires >=3 current-day 04:00–09:30 ET 5-minute candles.
Scores PM VWAP, EMA structure, 30m momentum, three closes above PM VWAP, relative PM volume when available, gap/chase risk, market/sector confirmation and event risk.
Ineligible Analyze reason reports PM candle count, total candles, and newest candle NY timestamp.
Labels expire automatically outside PRE because the PM engine only runs in phase PRE.
Regular-session thresholds, provider routing, PostgreSQL, Outcome Engine and calendar are unchanged.
