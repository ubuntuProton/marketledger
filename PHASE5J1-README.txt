MarketLedger Pro V5J.1 — Extended-Hours Engine Startup Hotfix

Root cause of V5J blank/zero dashboard:
A missing opening quote in one V5J JavaScript string caused a browser JavaScript parse error.
Because the bundle could not parse, initialization never ran: watchlist remained 0,
provider health stayed waiting, calendar/storage/outcomes stayed loading, and the board was empty.

V5J.1:
- fixes that JavaScript syntax error
- validates the complete inline JavaScript with `node --check` before packaging
- retains the V5J Extended-Hours / Overnight Setup Engine
- retains V5I.4 compact board/no-jump Analyze behavior
- does not modify PostgreSQL data or signal history
- does not change regular-session thresholds

No database restore or re-import is required.
