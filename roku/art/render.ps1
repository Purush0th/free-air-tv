# Renders the Roku channel's artwork into roku\images with headless Chrome, from the same
# sources as the Fire TV app (art\freeair-icon.svg, art\banner.html), then draws the UI bitmaps.
# Usage: powershell -ExecutionPolicy Bypass -File roku\art\render.ps1
# Needs Chrome, Node and network access (the pages load Plus Jakarta Sans from Google Fonts).
$ErrorActionPreference = 'Stop'
$chrome = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
            "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
            "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) { throw 'Chrome not found.' }

$here = $PSScriptRoot
$images = Join-Path (Split-Path $here) 'images'
$repoArt = Join-Path (Split-Path (Split-Path $here)) 'art'
function Url($file) { 'file:///' + ($file -replace '\\', '/') }

function Shot($page, $w, $h, $scale, $out) {
    New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
    if (Test-Path $out) { Remove-Item $out }
    # Chrome logs harmless notices on stderr; don't let Windows PowerShell treat them as errors.
    $ErrorActionPreference = 'Continue'
    & $chrome --headless=new --disable-gpu --disable-lcd-text --hide-scrollbars --allow-file-access-from-files `
        --virtual-time-budget=8000 --default-background-color=00000000 `
        --force-device-scale-factor=$scale --window-size=$w,$h "--screenshot=$out" (Url $page) 2>$null | Out-Null
    $deadline = (Get-Date).AddSeconds(30)
    while (-not (Test-Path $out) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 300 }
    if (-not (Test-Path $out)) { throw "Render failed: $out" }
    Write-Host "  $out"
}

# Channel poster on the Roku home screen: 540x405 (FHD) and 290x218 (HD).
Shot "$here\poster.html" 540 405 1 "$images\poster_fhd.png"
# Chrome will not open a window that small, so HD is laid out at 580x436 and drawn at half scale.
Shot "$here\poster.html" 580 436 0.5 "$images\poster_hd.png"

# Splash: the Fire TV banner layout (1280x720) at 1.5x for FHD and 1x for HD.
Shot "$repoArt\banner.html" 1280 720 1.5 "$images\splash_fhd.png"
Shot "$repoArt\banner.html" 1280 720 1 "$images\splash_hd.png"

# Channel list header logo: 72 px (the Fire TV guide's 36dp at FHD).
Shot "$repoArt\icon.html" 200 200 0.36 "$images\logo_mark.png"

& node "$here\ui-assets.js"
if ($LASTEXITCODE -ne 0) { throw 'ui-assets.js failed' }
& node "$here\icons.js"
if ($LASTEXITCODE -ne 0) { throw 'icons.js failed' }
