MarketLedger Pro V5L.1 - Opening Intelligence Architecture

Purpose
- Move opportunity discovery earlier instead of waiting for regular-session confirmation.

Changes
- Pre-open score remains independent of regular-session BUY/STRONG scoring.
- Adds sector-peer breadth from the current watchlist.
- Adds a liquid AI/technology discovery universe so candidates such as META and ARM can surface even when not on the permanent watchlist.
- Persists browser-side fixed pre-open snapshots at 08:00, 08:30, 09:00, 09:15 and 09:25 ET.
- Opening engine receives the latest fixed pre-open snapshot as a prior; it only adds a modest handoff bonus when opening momentum/VWAP agree.
- If a pre-open leader loses opening momentum, the handoff is explicitly not confirmed.
- Opening Radar shows WATCHLIST vs DISCOVERED candidates.
- Live Board CSV adds pre-open sector breadth, source and opening handoff score.

Important
- This is decision support, not a prediction engine or automatic trade instruction.
- No regular-session STRONG/BUY thresholds were loosened.
- No database schema migration.
