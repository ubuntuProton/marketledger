MARKETLEDGER PRO — CLOUD + IPHONE PWA
=====================================

WHAT THIS BUILD DOES
--------------------
This build is designed to run MarketLedger Pro on an always-on HTTPS web service.
Your iPhone can then use MarketLedger from Wi-Fi or cellular even when your Windows PC is off.

Security changes in this build:
- Password login protects the dashboard and all /api endpoints.
- Login session uses an HttpOnly SameSite=Strict cookie.
- On Render the cookie is Secure (HTTPS only).
- Alpaca credentials can be supplied as server-side environment variables.
- Alpaca secret is not embedded in the PWA or Docker image.
- The service worker does NOT cache the protected dashboard page.
- /healthz is the only intentionally unauthenticated health endpoint.

Persistence changes:
- MARKETLEDGER_DATA_DIR can point at a persistent cloud disk.
- render.yaml uses /var/data/marketledger on a 1 GB persistent disk.
- stocks.tsv, calls.tsv, notes.tsv, events.tsv, signals.tsv, settings.properties,
  and backups remain under that persistent directory.

IMPORTANT
---------
A persistent disk is required for the current TSV-based MarketLedger storage model.
Do not deploy this version on ephemeral storage if you want watchlists, signals,
settings, and calibration history to survive redeploys/restarts.

RENDER DEPLOYMENT
-----------------
1. Create a PRIVATE GitHub repository, for example: marketledger-pro-cloud
2. Upload the contents of this folder to that repository.
   Do NOT upload your Alpaca key/secret or a .env file.
3. In Render, choose New -> Blueprint and connect the repository.
4. Render detects render.yaml.
5. When prompted for environment variables, enter:
     MARKETLEDGER_PASSWORD = a strong password only you know
     ALPACA_API_KEY        = your Alpaca API key
     ALPACA_API_SECRET     = your Alpaca API secret
6. Deploy the Blueprint.
7. When deployment finishes, Render gives the service an HTTPS URL such as:
     https://marketledger-pro-xxxx.onrender.com
8. Open that HTTPS address in Safari on the iPhone.
9. Sign in with MARKETLEDGER_PASSWORD.
10. Safari -> Share -> Add to Home Screen.

The iPhone Home Screen icon now opens the cloud MarketLedger dashboard.
Your Windows PC does not need to be running.

DATA NOTE
---------
This cloud instance has its own persistent MarketLedger data directory. Your existing
Windows %LOCALAPPDATA%\MarketLedgerPro files are NOT automatically copied to the cloud.
Keep the Windows copy as a backup. A migration/import step can be added separately.

LOCAL TEST
----------
Windows PowerShell example:
  $env:PORT="8080"
  $env:MARKETLEDGER_PASSWORD="choose-a-test-password"
  $env:MARKETLEDGER_DATA_DIR="$env:LOCALAPPDATA\MarketLedgerPro"
  java -jar MarketLedger-Pro-Cloud-iPhone.jar

Then open http://localhost:8080 and sign in.

CLOUD VARIABLES
---------------
PORT                     Hosting platform port (Render supplies this automatically)
MARKETLEDGER_DATA_DIR     Persistent data directory
MARKETLEDGER_PASSWORD     Required cloud login password
ALPACA_API_KEY            Optional server-side Alpaca API key
ALPACA_API_SECRET         Optional server-side Alpaca API secret

NOTES
-----
- Yahoo remains the candle fallback.
- Alpaca remains the authenticated quote/microstructure provider when configured.
- MarketLedger is decision-support software; setup labels do not guarantee future results.
