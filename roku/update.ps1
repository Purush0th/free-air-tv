# Installs FreeAir TV on a Roku from this PC: the newest Roku release on GitHub, or a zip you
# give it. A sideloaded channel can't update itself; Roku only lets its developer installer
# (http://<roku-ip>, user rokudev) replace it, so this does that upload for you.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File roku\update.ps1                 # newest release
#   powershell -ExecutionPolicy Bypass -File roku\update.ps1 -Zip build\FreeAirTV-roku-0.1.6.zip
#   ... -Roku 192.168.1.107     (otherwise the last address used, or it asks)
#
# It asks for the developer password you set when you turned on developer mode; the password
# is only sent to the Roku.
param(
    [string]$Roku,
    [string]$Zip
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Remember the Roku's address for next time.
$saved = Join-Path $env:LOCALAPPDATA 'FreeAirTV\roku-ip.txt'
if (-not $Roku -and (Test-Path $saved)) { $Roku = (Get-Content $saved -Raw).Trim() }
if (-not $Roku) { $Roku = Read-Host 'Roku IP address (Settings > Network > About on the Roku)' }
New-Item -ItemType Directory -Force (Split-Path $saved) | Out-Null
Set-Content $saved $Roku

if (-not $Zip) {
    Write-Host 'Looking for the newest FreeAir TV for Roku release on GitHub...'
    $releases = Invoke-RestMethod -Uri 'https://api.github.com/repos/Purush0th/free-air-tv/releases?per_page=30' `
        -Headers @{ 'User-Agent' = 'FreeAirTV-update.ps1' }
    $release = $releases | Where-Object { $_.tag_name -like 'roku-v*' } | Select-Object -First 1
    if (-not $release) { throw 'No Roku release (roku-v* tag) found on GitHub.' }
    $asset = $release.assets | Where-Object { $_.name -like '*.zip' } | Select-Object -First 1
    if (-not $asset) { throw "Release $($release.tag_name) has no zip." }
    $outDir = Join-Path (Split-Path $PSScriptRoot) 'build'
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $Zip = Join-Path $outDir $asset.name
    Write-Host "Downloading $($asset.name)..."
    Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $Zip -UseBasicParsing
    $sum = $release.assets | Where-Object { $_.name -eq "$($asset.name).sha256" } | Select-Object -First 1
    if ($sum) {
        $expected = ((Invoke-WebRequest -Uri $sum.browser_download_url -UseBasicParsing).Content -split '\s+')[0]
        $actual = (Get-FileHash $Zip -Algorithm SHA256).Hash
        if ($actual -ne $expected.ToUpper()) { throw "Checksum mismatch for $($asset.name); not installing." }
    }
}
$Zip = (Resolve-Path $Zip).Path

$password = Read-Host "Developer password for the Roku at $Roku (user rokudev)" -AsSecureString
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.Credentials = New-Object System.Net.NetworkCredential('rokudev', $password)
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromMinutes(2)
try {
    $form = New-Object System.Net.Http.MultipartFormDataContent
    $form.Add((New-Object System.Net.Http.StringContent('Install')), 'mysubmit')
    $bytes = New-Object System.Net.Http.ByteArrayContent(, [IO.File]::ReadAllBytes($Zip))
    $bytes.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/zip')
    $form.Add($bytes, 'archive', [IO.Path]::GetFileName($Zip))
    Write-Host "Installing $([IO.Path]::GetFileName($Zip)) on $Roku..."
    $response = $client.PostAsync("http://$Roku/plugin_install", $form).Result
    $html = $response.Content.ReadAsStringAsync().Result
} finally {
    $client.Dispose()
}

if ($response.StatusCode -eq [Net.HttpStatusCode]::Unauthorized) { throw 'The Roku refused the password.' }
if (-not $response.IsSuccessStatusCode) { throw "The Roku answered $([int]$response.StatusCode) $($response.ReasonPhrase)." }
# The installer page reports in a list of messages; show the ones that matter.
$messages = [regex]::Matches($html, '"text"\s*:\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
if (-not $messages) { $messages = [regex]::Matches($html, '<font color="red">([^<]+)') | ForEach-Object { $_.Groups[1].Value } }
$messages | Select-Object -Unique | ForEach-Object { Write-Host "  Roku: $_" }
if ($html -match 'Install Success|Identical to previous version') {
    Write-Host 'Done. FreeAir TV is installed and starting on the Roku.'
} else {
    Write-Host 'The Roku did not report success; see its messages above.'
    exit 1
}
