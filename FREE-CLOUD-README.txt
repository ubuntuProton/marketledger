MARKETLEDGER PRO - FREE RENDER TEST DEPLOYMENT

Purpose
- Runs MarketLedger Pro on Render's Free web-service plan.
- Gives the iPhone an HTTPS URL that works without the Windows PC being on.
- Keeps password authentication and server-side Alpaca environment variables.

IMPORTANT STORAGE LIMITATION
- This free test uses Render's ephemeral local filesystem at /tmp/marketledger.
- Watchlist changes, signals, notes, events, and settings written by the cloud instance can be lost when the service spins down, restarts, or redeploys.
- Keep the Windows MarketLedger data as the authoritative copy during this test.
- Do not use this free deployment as permanent storage.

Render Blueprint secrets
Set these in Render when prompted. Never commit their values to GitHub:
- MARKETLEDGER_PASSWORD
- ALPACA_API_KEY
- ALPACA_API_SECRET

Deployment
1. Copy/replace render.yaml in the GitHub repository with the one in this package.
2. Commit and push.
3. In Render, create/sync the Blueprint from render.yaml.
4. Enter the three secret values above.
5. Deploy.
6. Open the resulting https://...onrender.com URL on iPhone Safari.
7. Safari Share > Add to Home Screen.

Free-tier behavior
- The service can spin down after inactivity and may take roughly a minute to wake.
- Local filesystem data is not durable.
