# Zips the Roku channel for sideloading: build\FreeAirTV-roku-<version>.zip
# Usage: powershell -ExecutionPolicy Bypass -File roku\package.ps1
#
# The zip holds manifest, source\, components\, images\ and the repository's channels.json
# (the built-in fallback list), with manifest at its root as Roku requires. It first redraws
# the Tamil channel names (art\labels.js, needs Node and Chrome) so they match channels.json.
# Entries are written with forward slashes: Windows PowerShell's Compress-Archive uses
# backslashes, which the Roku installer does not accept.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$roku = $PSScriptRoot
& node "$roku\art\labels.js"
if ($LASTEXITCODE -ne 0) { throw 'art\labels.js failed' }

$repo = Split-Path $roku
$manifest = Get-Content "$roku\manifest" -Encoding UTF8
function Field($name) { ($manifest | Where-Object { $_ -match "^$name=" }) -replace "^$name=", '' }
$version = "$(Field 'major_version').$(Field 'minor_version').$(Field 'build_version')"

$outDir = Join-Path $repo 'build'
New-Item -ItemType Directory -Force $outDir | Out-Null
$zipPath = Join-Path $outDir "FreeAirTV-roku-$version.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath }

# Published path in the zip -> file on disk.
$entries = [ordered]@{ 'manifest' = "$roku\manifest"; 'channels.json' = "$repo\channels.json" }
foreach ($dir in 'source', 'components', 'images') {
    Get-ChildItem "$roku\$dir" -File -Recurse | Sort-Object FullName | ForEach-Object {
        $entries[$_.FullName.Substring($roku.Length + 1) -replace '\\', '/'] = $_.FullName
    }
}

$zip = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
try {
    foreach ($name in $entries.Keys) {
        # PNGs are already compressed.
        $level = if ($name -match '\.png$') { 'NoCompression' } else { 'Optimal' }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $entries[$name], $name, $level) | Out-Null
    }
} finally {
    $zip.Dispose()
}
Write-Host "Packaged $($entries.Count) files: $zipPath ($([math]::Round((Get-Item $zipPath).Length / 1KB)) KB)"
