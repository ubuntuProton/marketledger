MarketLedger Pro V5D - Outcome Measurement Engine

- Keeps V5C credential hardening, mobile portrait UI, and native PostgreSQL schema.
- Adds a 5-minute background outcome worker.
- Uses historical 5-minute candles for reproducible +15/+30/+60 minute measurement.
- Requires comparable session data; CLOSED observations are intentionally not scored across session boundaries.
- Adds price/measured timestamp columns plus 60-minute maximum gain and maximum drawdown.
- Preserves measured PostgreSQL outcomes during subsequent TSV/native synchronization.
- Adds indexes for signal analytics and captured_at_ts TIMESTAMPTZ.

Calibration remains descriptive historical evidence, not a prediction of future returns.
