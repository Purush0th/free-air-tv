# Builds FreeAir TV on Windows without Gradle, using only Android SDK build tools.
#
#   powershell -ExecutionPolicy Bypass -File build.ps1 [-Version 1.2.0]
#
# Output: build\FreeAirTV-<version>.apk and build\FreeAirTV-<version>.apk.sha256
#
# Signing key: never stored in this repository. By default it lives in
#   %USERPROFILE%\.freeairtv\release.keystore  (password in keystore-password.txt beside it)
# and is created on the first build. Override with FREEAIRTV_KEYSTORE / FREEAIRTV_KEYSTORE_PASS.
# Back both files up: without them no future version can update the installed app.
param(
    [string]$Version = '0.0.0-dev'
)
$ErrorActionPreference = 'Stop'

$sdk      = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'E:\AppData\Android-Sdk' }
$bt       = Join-Path $sdk 'build-tools\35.0.0'
$platform = Join-Path $sdk 'platforms\android-35\android.jar'
$root     = $PSScriptRoot
$src      = Join-Path $root 'app\src\main'
$out      = Join-Path $root 'build'
$minSdk = 22; $targetSdk = 35

# versionCode from the version: 1.2.3 -> 10203.
$nums = @(($Version -split '[^0-9]+') | Where-Object { $_ -ne '' } | ForEach-Object { [int]$_ }) + @(0, 0, 0)
$versionCode = $nums[0] * 10000 + $nums[1] * 100 + $nums[2]
if ($versionCode -lt 1) { $versionCode = 1 }

# The GitHub repository the app updates from: env var, else the git remote.
$repo = $env:FREEAIRTV_REPO
if (-not $repo) {
    $ErrorActionPreference = 'Continue' # git writes "not a git repository" to stderr
    $remote = (& git -C $root remote get-url origin 2>&1) | Out-String
    $ErrorActionPreference = 'Stop'
    if ($remote -match 'github\.com[:/]([^\s]+?)(\.git)?\s*$') { $repo = $Matches[1] }
}
if (-not $repo) { $repo = 'OWNER/free-air-tv'; Write-Warning "No GitHub remote; updates will point at $repo." }

# Signing key, kept outside the repository.
$keyDir = Join-Path $env:USERPROFILE '.freeairtv'
$ks     = if ($env:FREEAIRTV_KEYSTORE) { $env:FREEAIRTV_KEYSTORE } else { Join-Path $keyDir 'release.keystore' }
$passFile = Join-Path $keyDir 'keystore-password.txt'
$alias  = 'freeairtv'

function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($exe)) failed ($LASTEXITCODE)" }
}
function WriteUtf8($path, $text) {
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding $false))
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex" | Out-Null

Write-Host "FreeAir TV $Version (code $versionCode), updates from github.com/$repo"

Write-Host '1/6 Compiling resources'
# Generated resources: the repo's channel list and the repository name.
Copy-Item (Join-Path $root 'channels.json') (New-Item -ItemType Directory -Force "$out\gen-res\raw").FullName
WriteUtf8 "$out\gen-res\values\config.xml" @"
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="github_repo" translatable="false">$repo</string>
    <string name="github_branch" translatable="false">main</string>
</resources>
"@
Run "$bt\aapt2.exe" @('compile', '--dir', "$src\res", '-o', "$out\res.zip")
Run "$bt\aapt2.exe" @('compile', '--dir', "$out\gen-res", '-o', "$out\gen-res.zip")

Write-Host '2/6 Linking manifest and resources'
Run "$bt\aapt2.exe" @('link', '-I', $platform,
    '--manifest', "$src\AndroidManifest.xml",
    '--min-sdk-version', "$minSdk", '--target-sdk-version', "$targetSdk",
    '--version-code', "$versionCode", '--version-name', $Version,
    '--java', "$out\gen", '-o', "$out\unaligned.apk", "$out\res.zip", "$out\gen-res.zip")

Write-Host '3/6 Compiling Java'
$javaFiles = @(Get-ChildItem "$src\java", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName)
Run 'javac' (@('-source', '8', '-target', '8', '-Xlint:-options', '-encoding', 'UTF-8',
    '-bootclasspath', $platform, '-classpath', "$bt\core-lambda-stubs.jar",
    '-d', "$out\classes") + $javaFiles)

Write-Host '4/6 Converting to DEX'
$classFiles = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
Run "$bt\d8.bat" (@('--release', '--min-api', "$minSdk", '--lib', $platform, '--output', "$out\dex") + $classFiles)

Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unaligned.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()

Write-Host '5/6 Aligning'
Run "$bt\zipalign.exe" @('-p', '-f', '4', "$out\unaligned.apk", "$out\aligned.apk")

Write-Host '6/6 Signing'
if ($env:FREEAIRTV_KEYSTORE_PASS) {
    $ksPass = $env:FREEAIRTV_KEYSTORE_PASS
} elseif (Test-Path $passFile) {
    $ksPass = (Get-Content $passFile -Raw).Trim()
} else {
    $ksPass = $null
}
if (-not (Test-Path $ks)) {
    if ($env:FREEAIRTV_KEYSTORE) { throw "Keystore not found: $ks" }
    New-Item -ItemType Directory -Force $keyDir | Out-Null
    $bytes = New-Object byte[] 24
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $ksPass = [Convert]::ToBase64String($bytes) -replace '[+/=]', 'x'
    WriteUtf8 $passFile $ksPass
    Run 'keytool' @('-genkeypair', '-keystore', $ks, '-storepass', $ksPass, '-keypass', $ksPass,
        '-alias', $alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
        '-dname', 'CN=FreeAir TV')
    Write-Warning "Created a new signing key in $keyDir. Back up release.keystore and keystore-password.txt now."
}
if (-not $ksPass) { throw "No keystore password: set FREEAIRTV_KEYSTORE_PASS or create $passFile" }

$apk = Join-Path $out "FreeAirTV-$Version.apk"
Run "$bt\apksigner.bat" @('sign', '--ks', $ks, '--ks-pass', "pass:$ksPass", '--ks-key-alias', $alias,
    '--out', $apk, "$out\aligned.apk")
Run "$bt\apksigner.bat" @('verify', $apk)

$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
WriteUtf8 "$apk.sha256" "$hash  FreeAirTV-$Version.apk`n"
Write-Host "Built $apk"
Write-Host "SHA-256 $hash"
