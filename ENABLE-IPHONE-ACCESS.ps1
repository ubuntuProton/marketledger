# Run this once as Administrator on the Windows PC.
$ErrorActionPreference = 'Stop'
$rule = Get-NetFirewallRule -DisplayName 'MarketLedger Pro iPhone' -ErrorAction SilentlyContinue
if (-not $rule) {
  New-NetFirewallRule -DisplayName 'MarketLedger Pro iPhone' -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Private | Out-Null
}
Write-Host ''
Write-Host 'MarketLedger TCP 8080 is allowed on PRIVATE Windows networks.' -ForegroundColor Green
Write-Host 'Keep the iPhone and PC on the same non-guest Wi-Fi.'
Write-Host 'Start MarketLedger, then use the Mobile URL printed by MarketLedger.'
