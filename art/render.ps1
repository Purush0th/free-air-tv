# Renders the FreeAir TV artwork in art\ into the app's PNG resources with headless Chrome.
# Usage: powershell -ExecutionPolicy Bypass -File art\render.ps1
# Needs Chrome and network access (banner.html loads Plus Jakarta Sans from Google Fonts).
#
# Images are rendered larger than their density bucket needs: TV launchers (Projectivy,
# the Fire TV home) scale banners and icons up to big tiles, and a small bitmap blurs.
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

# Fire TV banner: 1280x720 in drawable-xxxhdpi, which Android treats as the standard
# 320x180dp banner and scales down on the Stick. In a lower-density folder the same bitmap
# would claim to be larger than a banner, and Projectivy crops the overflow.
Remove-Item "$res\drawable-xhdpi\banner.png", "$res\drawable-xxhdpi\banner.png", "$res\drawable-xxxhdpi\banner.png" -ErrorAction SilentlyContinue
Shot 'banner.html' 1280 720 1 "$res\drawable-xxxhdpi\tv_banner.png"

# Launcher icon (48dp): one 192px copy in mipmap-xxxhdpi; Android scales it down on the Stick.
Remove-Item "$res\mipmap-xhdpi\ic_launcher.png", "$res\mipmap-xxhdpi\ic_launcher.png" -ErrorAction SilentlyContinue
Shot 'icon.html' 200 200 0.96 "$res\mipmap-xxxhdpi\ic_launcher.png"

# The guide and settings header logo (40dp): 160px, twice what xhdpi needs.
Shot 'icon.html' 200 200 0.8 "$res\drawable-xhdpi\logo_mark.png"
