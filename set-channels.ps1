# Tests a channel list on a Fire TV over ADB without publishing it to GitHub.
# A pushed list takes precedence over GitHub's until it is removed with -Reset.
#
#   .\set-channels.ps1 -File my-channels.json   # validate, then push to the TV
#   .\set-channels.ps1 -Reset                    # remove it; the app goes back to GitHub's list
#
# To change the list for every TV, edit channels.json in the repository instead.
param(
    [string]$File,
    [switch]$Reset,
    [string]$Device = '192.168.1.18:5555'
)
$package = 'com.purush.freeairtv'
$remote = "/sdcard/Android/data/$package/files/channels.json"

# adb prints harmless notices on stderr; pass them through as text.
function Invoke-Adb { & adb.exe -s $Device @args 2>&1 | ForEach-Object { "$_" } }

function Restart-App {
    Invoke-Adb shell am force-stop $package | Out-Null
    Invoke-Adb shell am start -n "$package/.MainActivity" | Out-Null
}

& adb.exe connect $Device | Out-Null

if ($Reset) {
    Invoke-Adb shell rm -f $remote | Out-Null
    Restart-App
    'Removed the pushed list; FreeAir TV uses the GitHub list again.'
    return
}
if (-not $File) { throw 'Pass -File <channels.json> or -Reset' }

# Validate before pushing, with the same rules the app applies.
$list = @(Get-Content $File -Raw -Encoding UTF8 | ConvertFrom-Json)
if ($list.Count -eq 0) { throw 'The file has no channels.' }
$ids = @{}
$i = 0
foreach ($ch in $list) {
    $i++
    if (-not $ch.name) { throw "Channel $i has no name." }
    if ($ch.url -notmatch '^https?://') { throw "Channel $i ($($ch.name)) needs an http(s) url." }
    $id = if ($ch.id) { $ch.id } else { $ch.name }
    if ($ids.ContainsKey($id)) { throw "Duplicate id '$id'." }
    $ids[$id] = $true
}

Invoke-Adb shell mkdir -p "/sdcard/Android/data/$package/files" | Out-Null
Invoke-Adb push $File $remote
Restart-App
"Pushed $($list.Count) channels and restarted FreeAir TV."
