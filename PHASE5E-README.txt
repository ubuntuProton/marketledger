MarketLedger Pro V5E — Upcoming Earnings Alert Engine

New:
- Automatic watchlist earnings-calendar sync every 6 hours.
- Manual "Sync earnings now" control.
- Upcoming Earnings dashboard panel sorted by nearest event.
- BMO / AMC / TBD timing labels when the source timestamp supports classification.
- Browser/iPhone PWA notification when a loaded watchlist earnings event is within 3 days.
- Notification de-duplication by ticker + earnings date.
- Auto-synced earnings stored as HIGH EARNINGS events in the existing event system.
- Earnings risk gate starts 24 hours before the scheduled event and remains active briefly afterward.
- Earnings events never add bullish/bearish points; they can only constrain the final setup status.
- Existing V5D Outcome Engine and V5D.2 prior-session technical labeling retained.

Data-source note:
The built-in earnings discovery uses Yahoo calendarEvents as a best-effort public source. If the provider
does not return a date for a symbol, MarketLedger leaves that ticker unchanged rather than inventing one.
Manual EARNINGS events remain supported.

Notification note:
Browser/PWA notification permission must be enabled. The server keeps earnings data synced even when the
browser is closed; standard browser notifications are evaluated when the MarketLedger PWA/page is active.
