# Renders the FreeAir TV artwork in art\ into the app's PNG resources with headless Chrome.
# Usage: powershell -ExecutionPolicy Bypass -File art\render.ps1
# Needs Chrome and network access (banner.html loads Plus Jakarta Sans from Google Fonts).
#
# The banner is drawn at its exact pixel size; the icon one bucket up, since launchers show
# icons larger than 48dp.
$ErrorActionPreference = 'Stop'
$chrome = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
            "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
            "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) { throw 'Chrome not found.' }

$art = $PSScriptRoot
$res = Join-Path (Split-Path $art) 'app\src\main\res'
$artUrl = 'file:///' + ($art -replace '\\', '/')

function Shot($page, $w, $h, $scale, $out) {
    New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
    if (Test-Path $out) { Remove-Item $out }
    # Chrome logs harmless notices on stderr; don't let Windows PowerShell treat them as errors.
    $ErrorActionPreference = 'Continue'
    & $chrome --headless=new --disable-gpu --hide-scrollbars --allow-file-access-from-files `
        --virtual-time-budget=8000 --default-background-color=00000000 `
        --force-device-scale-factor=$scale --window-size=$w,$h "--screenshot=$out" "$artUrl/$page" 2>$null | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $out) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (-not (Test-Path $out)) { throw "Render failed: $out" }
    Write-Host "  $out"
}

# Fire TV banner: 320x180 px in drawable-xhdpi, Amazon's banner size and what Plex and
# Tailscale ship. Launchers show it at about tile size, so it is drawn at its final pixel size
# (the 1280x720 layout at quarter scale) rather than shrunk from a big bitmap, which blurs.
Remove-Item "$res\drawable-xxhdpi\tv_banner.png", "$res\drawable-xxxhdpi\tv_banner.png" -ErrorAction SilentlyContinue
Shot 'banner.html' 1280 720 0.25 "$res\drawable-xhdpi\tv_banner.png"

# Launcher icon (48dp): one 192px copy in mipmap-xxxhdpi; Android scales it down on the Stick.
Remove-Item "$res\mipmap-xhdpi\ic_launcher.png", "$res\mipmap-xxhdpi\ic_launcher.png" -ErrorAction SilentlyContinue
Shot 'icon.html' 200 200 0.96 "$res\mipmap-xxxhdpi\ic_launcher.png"

# The guide and settings header logo (40dp): 160px, twice what xhdpi needs.
Shot 'icon.html' 200 200 0.8 "$res\drawable-xhdpi\logo_mark.png"
