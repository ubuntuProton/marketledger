MARKET LEDGER PRO 2.0
=====================
Run: java -jar MarketLedger-Pro.jar
PC: http://localhost:8080
Phone/tablet: keep the PC app running, use the same Wi-Fi, then open the LAN address printed in the app header/console.

Features
- Responsive PC/mobile web UI
- 5-minute candlestick chart via a best-effort public market-data endpoint
- Rule-based technical score using EMA9/EMA21, RSI(14), VWAP, 30-minute momentum and relative volume
- Color-coded STRONG SETUP / BUY SETUP / HOLD / WAIT & SEE / AVOID-NOT NOW labels
- Existing prediction ledger and persistent ./data files remain compatible
- CSV export

Data and signal warning
Market data can be delayed, unavailable or revised. The technical labels are deterministic decision-support indicators, not guarantees or personalized investment advice. They do not include your portfolio, taxes, risk tolerance, order book, all news, options positioning or fundamental valuation.

Mobile security
The server binds to your LAN so a phone on the same Wi-Fi can connect. Windows Firewall may prompt you to allow Private Network access. Do not forward/expose port 8080 to the public internet.

Windows native package
Run BUILD-WINDOWS.bat on a Windows x64 PC with JDK 21 installed once. It creates a self-contained app image with bundled Java runtime. After that Java is not required to run the packaged app.

VERSION 2.1 - TIME-AWARE DECISION ENGINE
----------------------------------------
The technical board now applies New York (America/New_York) market-phase gates on top of the candle score:
- Overnight: WAIT / historical context only
- 4:00-9:30 ET pre-market: WAIT for regular-session confirmation
- 9:30-10:30 ET: opening no-chase / WAIT gate
- 10:30-11:30 ET: price-discovery analysis
- 11:30-13:30 ET: confirmation window; STRONG SETUP requires stronger multi-factor confirmation
- 13:30-15:30 ET: afternoon late-entry/chase checks
- 15:30-16:00 ET: closing/rebalance volatility WAIT gate
- After-hours: WAIT / thinner-liquidity context

Best-practice checks include regular-session VWAP, EMA9/EMA21 trend, RSI(14), 30-minute momentum, relative volume, 3-candle VWAP confirmation, ATR-normalized extension/chase detection, opening-price extension, stale-data detection, and automatic de-rating of technically strong but poorly timed entries.

These are rule-based technical setup labels, not trade guarantees. Public candle data can be delayed or unavailable. The app does not place orders.

VERSION 2.2 - PERSISTENT WINDOWS STORAGE
----------------------------------------
MarketLedger no longer stores its watchlist relative to the launch folder.
On Windows it uses: %LOCALAPPDATA%\MarketLedgerPro\
This preserves stocks, prediction calls and notes across restarts, shortcuts and app upgrades.
On first launch, an older .\data folder is migrated automatically when the permanent store is empty.
Daily snapshots are kept under backups\ (latest 14 backup dates retained).
Advanced override: -Dmarketledger.dataDir=C:\path\to\folder

VERSION 2.2 - CONTEXT + EXTENDED HOURS
--------------------------------------
- Adds persistent Context/Event Engine (events.tsv) under the permanent MarketLedgerPro data folder.
- Context types: ECONOMIC, FED, EARNINGS, REBALANCE, OPTIONS_EXPIRATION, NEWS.
- HIGH events gate technical BUY/STRONG signals to WAIT around the event window; MEDIUM events cap fresh entries to HOLD.
- Context is a risk/confirmation overlay, not a directional prediction.
- Adds separate pre-market, after-hours, and overnight volume totals from the available 5-minute extended-hours candle feed.
- Pre-market display follows 7:00-9:25 ET (6:00-8:25 Central) where Schwab supports pre-market execution.
- After-hours display follows 4:00-8:00 ET candles; Schwab execution starts at 4:05 ET.
- Overnight display is shown when the upstream candle provider supplies it. Overnight tradability depends on symbol/broker eligibility.
- Prevents overlapping automatic 60-second analysis refreshes.
- The initial context file seeds selected events from the Schwab Week Ahead supplied for Sept. 20, 2026. Add/update future weekly events in the Context & Event Engine.

VERSION 2.3 - AUTO CONTEXT + MARKET/SECTOR CONFIRMATION
------------------------------------------------------
- Adds one-click "Sync latest Schwab Week Ahead". The app discovers the latest public Week Ahead article on Schwab Network, extracts dated/timed economic/Fed calendar items, de-duplicates them, and stores them in the persistent events.tsv database.
- Adds broad-market confirmation using SPY and QQQ plus a sector ETF mapping (for example SMH for semiconductors, XBI for biotech, XLU for utilities). If multiple benchmarks conflict with a fresh BUY/STRONG setup, the final state is capped to HOLD rather than treating a single-stock chart in isolation.
- Benchmark confirmation is contextual/risk information, not a prediction and not an automatic trade instruction.
- Extended-hours calculations remain enabled. Public Yahoo candles can include pre/post-market data, but true 24/5 overnight coverage is provider-dependent and may be absent.
- A future authenticated market-data provider can replace the public candle endpoint without changing the decision-engine design.

VERSION 2.4 - MARKET DATA PROVIDER V2
-------------------------------------
- Yahoo remains the resilient 5-minute candle fallback.
- Optional Alpaca authentication is configured inside the UI and stored only in the local MarketLedgerPro settings.properties file.
- Adds bid/ask, displayed quote sizes, spread %, provider/feed visibility, and extended-hours liquidity gates.
- During PRE / POST / OVERNIGHT, very wide spreads force WAIT and elevated spreads/thin displayed liquidity cap positive setups.
- Alpaca overnight feed is selected by the backend during the overnight phase; regular/pre/post use IEX on the free tier.
- Market/context/sector/event gates remain active.
- No order execution is implemented.

VERSION 2.5 - Signal Memory & Calibration
- Persists every unique analyzed 5-minute signal snapshot in signals.tsv.
- Automatically fills forward 15/30/60-minute returns as later ticker analyses arrive.
- Calibration dashboard shows sample counts, average forward return, and percent-positive outcomes by signal label.
- Stores technical score, phase, RSI, VWAP, trend, relative volume, ATR, spread, market/sector context, and nearby event context for each snapshot.
- Results are descriptive historical measurements, not predictions or guarantees.

VERSION 2.6 - CALIBRATION ENGINE
--------------------------------
- Adds a Calibration Explorer backed by persisted signals.tsv history.
- Breakdowns: market session, ticker, score band, bid/ask spread band, SPY/QQQ/sector confirmation, nearby-event state, and signal label.
- Shows 15/30/60-minute sample counts, average forward return, percent-positive outcomes, and sample sufficiency.
- Default minimum sample threshold is 20 and can be raised to 30/50/100 in the UI.
- Small samples are explicitly marked EARLY; MarketLedger does not auto-rewrite signal rules from calibration results.
- Calibration is descriptive historical evidence, not a prediction or guarantee.


VERSION 2.6 - REAL-TIME SETUP RANKING
- Technical board automatically sorts final STRONG SETUP first, then BUY SETUP, HOLD, WAIT, AVOID.
- Within each final status, ranking uses technical score plus market/sector confirmation, data freshness, spread quality and momentum.
- Stale/event/liquidity gates remain authoritative: a high raw score cannot outrank a safer final-status category.
- Optional board sorts: Setup strength (default), Technical score, Ticker.
- Shows last completed full-analysis time in New York time.
- Refresh cycle remains 60 seconds; manual Refresh analysis is available.

VERSION 2.7 - Reliability + Alerts
- Real-time board remains automatically ranked by final setup status, then quality factors.
- Provider health badge reports successful candle coverage and authenticated live quotes each cycle.
- Optional browser notification when a ticker transitions into STRONG SETUP (requires user permission).
- U.S. equity calendar guard includes major NYSE holidays plus Good Friday and common 1:00 p.m. ET early-close handling for the day after Thanksgiving and Christmas Eve.
- Holiday/early-close state gates live setup labels to WAIT/CLOSED semantics rather than treating the clock as a normal session.
- Existing event, spread/liquidity, stale-data, SPY/QQQ/sector, and no-chase gates remain in force.


DASHBOARD UX FIX 2.1
- Fixed embedded index.html resource lookup.
- The Windows ZIP now includes the matching JAR next to BUILD-WINDOWS.bat.
- BUILD-WINDOWS.bat also checks common JDK 17/21 jpackage locations.
