MarketLedger Pro Cloud/iPhone V3 - Persistent Database
======================================================

Purpose
-------
V3 moves MarketLedger's mutable data out of the disposable Render web-service filesystem and into PostgreSQL.
The existing TSV format is retained internally for compatibility, but every write is mirrored transactionally to a PostgreSQL table and rehydrated when the web container starts.

Persisted data
--------------
- stocks.tsv (watchlist)
- calls.tsv (Prediction Ledger)
- notes.tsv
- events.tsv
- signals.tsv (Signal Memory / calibration history)
- settings.properties (provider selection; Render environment credentials still override stored credentials)

Deployment
----------
1. BEFORE deploying V3, open the current V2 MarketLedger and click Download backup.
2. Copy this package over the Git repository and push to main.
3. Render Blueprint sync will propose a new PostgreSQL database named marketledger-db.
4. The database uses the smallest paid Render Postgres compute plan in render.yaml. Render will require payment information. This is expected for durable storage; Render's free Postgres expires after 30 days and is not permanent.
5. Allow the Blueprint sync/deploy to finish.
6. Open MarketLedger. Cloud Data Safety should show: Storage: PostgreSQL • PERSISTENT.
7. Restore the V2 backup once. V3 writes the restored data into PostgreSQL.
8. Add a test ticker, then redeploy/restart the web service and verify the ticker remains.

Security
--------
DATABASE_URL is injected by Render from the database resource and is not committed to GitHub.
The database has no public IP allow list (private Render networking only).
Alpaca credentials remain Render environment variables and are not included in portable backups.

Important
---------
Keep occasional Download Backup copies even with PostgreSQL. Database persistence protects against web-container replacement; portable backups protect against accidental application-level deletion or other operational mistakes.
