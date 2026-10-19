# Renders the FreeAir TV artwork in art\ into the app's PNG resources with headless Chrome.
# Usage: powershell -ExecutionPolicy Bypass -File art\render.ps1
# Needs Chrome and network access (banner.html loads Plus Jakarta Sans from Google Fonts).
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

# Fire TV banner: 320x180 (xhdpi) and 480x270 (xxhdpi), drawn at 640x360.
Shot 'banner.html' 640 360 0.5  "$res\drawable-xhdpi\banner.png"
Shot 'banner.html' 640 360 0.75 "$res\drawable-xxhdpi\banner.png"

# Launcher icon (48dp) and the guide's header logo (40dp), from the 200x200 icon.
Shot 'icon.html' 200 200 0.48 "$res\mipmap-xhdpi\ic_launcher.png"
Shot 'icon.html' 200 200 0.72 "$res\mipmap-xxhdpi\ic_launcher.png"
Shot 'icon.html' 200 200 0.96 "$res\mipmap-xxxhdpi\ic_launcher.png"
Shot 'icon.html' 200 200 0.4  "$res\drawable-xhdpi\logo_mark.png"
