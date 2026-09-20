@echo off
setlocal
cd /d "%~dp0"
where jpackage >nul 2>nul
if errorlevel 1 (
  if exist "C:\Program Files\Java\jdk-21.0.12.1\bin\jpackage.exe" set "PATH=C:\Program Files\Java\jdk-21.0.12.1\bin;%PATH%"
  if exist "C:\Program Files\Java\jdk-17\bin\jpackage.exe" set "PATH=C:\Program Files\Java\jdk-17\bin;%PATH%"
)
where jpackage >nul 2>nul || (echo ERROR: jpackage not found. Install/use JDK 17 or 21, then run this file again.& pause & exit /b 1)
if not exist MarketLedger-Pro-iPhonePWA.jar (echo ERROR: Put MarketLedger-Pro-iPhonePWA.jar next to this BUILD-WINDOWS.bat file.& pause & exit /b 1)
if exist dist rmdir /s /q dist
mkdir dist
jpackage --type app-image --name "MarketLedger Pro" --input . --main-jar MarketLedger-Pro-iPhonePWA.jar --main-class app.MarketLedger --dest dist --vendor "Local Market Tools" --description "Local candle analysis and prediction ledger"
if errorlevel 1 (echo Build failed.& pause & exit /b 1)
echo.
echo SUCCESS. Your self-contained Windows app is in:
echo %CD%\dist\MarketLedger Pro\
echo Double-click "MarketLedger Pro.exe". Java is bundled inside that folder.
pause
