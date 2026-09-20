MARKETLEDGER PRO - IPHONE HOME SCREEN BUILD

1. This is an iPhone-ready PWA front end for the existing MarketLedger Java backend.
2. Your PC still runs the MarketLedger engine. The iPhone is the mobile client.
3. On Windows, right-click ENABLE-IPHONE-ACCESS.ps1 and run with PowerShell as Administrator once.
4. Start MarketLedger Pro on the PC.
5. Make sure Windows Wi-Fi is a PRIVATE network and iPhone is on the same non-guest Wi-Fi.
6. On iPhone Safari open the Mobile URL printed by MarketLedger, e.g. http://192.168.1.25:8080
7. Safari Share -> Add to Home Screen -> Add.

IMPORTANT:
- Do not expose TCP 8080 directly to the public Internet/router.
- Full service-worker/offline PWA behavior requires HTTPS. On plain home-LAN HTTP, the Home Screen launcher/mobile layout still works, but iOS may not enable service-worker caching.
- For access away from home / without the PC running, MarketLedger needs an authenticated HTTPS-hosted backend. This package does not pretend to provide cloud hosting.
