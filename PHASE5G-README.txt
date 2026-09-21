MarketLedger Pro V5G — Public Corporate Calendar Providers

Primary providers:
- Earnings: Xoomar public SEC-derived earnings calendar (no additional API key).
- IPOs: Nasdaq public IPO calendar, current month plus next 3 months (no additional API key).

Fallbacks:
- Alpha Vantage retained as secondary fallback when a primary provider request actually fails.
- Yahoo retained only as final earnings fallback.

Reliability:
- Existing provider diagnostics retained.
- Valid zero-match responses are successes and do not trigger unnecessary fallbacks.
- Cached corporate-calendar events remain preserved.
- No new secret/environment variable is required.
- Existing V5D through V5F.1 functionality retained.

Notes:
- Xoomar estimated dates are derived from prior SEC filing cadence; they are calendar estimates until confirmed.
- Nasdaq states expected IPO dates are estimates and can change.
- Calendar alerts are risk/context notifications, not directional trading calls.
