MarketLedger Pro V5C
====================

Changes
- Cloud Alpaca credentials are read only from Render environment variables.
- Existing settings.properties blob is deleted from PostgreSQL on startup in cloud mode.
- settings.properties is sanitized to non-secret provider configuration.
- iPhone portrait cards always show Analyze and Delete actions.
- Adds native PostgreSQL tables: watchlist, signal_observations, signal_outcomes, market_events, research_notes, prediction_calls.
- Existing marketledger_files storage remains temporarily for compatibility and rollback.
- Existing TSV data is migrated/synchronized into native tables automatically at startup.

No Render environment-variable changes are required.
