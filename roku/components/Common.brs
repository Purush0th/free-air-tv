' Helpers shared by MainScene and SettingsScreen.

function isString(v as dynamic) as boolean
    return GetInterface(v, "ifString") <> invalid
end function

function textOf(v as dynamic, fallback as string) as string
    if isString(v) then return v
    return fallback
end function

' A set of ids kept in the registry as a JSON array, read into an associative array.
function loadIdSet(reg as object, key as string) as object
    set = {}
    if reg.Exists(key)
        ids = ParseJson(reg.Read(key))
        if type(ids) = "roArray"
            for each id in ids
                if isString(id) then set[id] = true
            end for
        end if
    end if
    return set
end function

sub saveIdSet(reg as object, key as string, set as object)
    ids = []
    for each k in set
        ids.Push(k)
    end for
    reg.Write(key, FormatJson(ids))
    reg.Flush()
end sub

' Pre-drawn Tamil text (art/labels.js): from the package if it is there, else from GitHub,
' where the labels for channels added after this build are committed.
function labelFile(name as string) as string
    if m.labels = invalid
        ' Files in the package, listed by art/labels.js (roFileSystem isn't allowed on this thread).
        m.labels = {}
        for each f in ReadAsciiFile("pkg:/images/labels/index.txt").Tokenize(Chr(10))
            m.labels[f.Trim()] = true
        end for
    end if
    if m.labels.DoesExist(name) then return "pkg:/images/labels/" + name
    return "https://raw.githubusercontent.com/Purush0th/free-air-tv/main/roku/images/labels/" + name
end function

' A channel's Tamil name as an image, named by the MD5 of its text.
function labelUri(text as string, size as integer) as string
    text = text.Trim()
    if text = "" then return ""
    bytes = CreateObject("roByteArray")
    bytes.FromAsciiString(text)
    digest = CreateObject("roEVPDigest")
    digest.Setup("md5")
    return labelFile(digest.Process(bytes) + "-" + size.ToStr() + ".png")
end function

' A language's own name as an image, e.g. தமிழ் for ta-IN.
function langLabelUri(lang as string, size as integer) as string
    return labelFile("lang-" + lang + "-" + size.ToStr() + ".png")
end function

function appVersion() as string
    return CreateObject("roAppInfo").GetVersion()
end function

' True if version a (e.g. "0.1.10") is newer than b.
function isNewer(a as string, b as string) as boolean
    x = a.Tokenize(".")
    y = b.Tokenize(".")
    for i = 0 to 2
        p = 0
        q = 0
        if i < x.Count() then p = x[i].ToInt()
        if i < y.Count() then q = y[i].ToInt()
        if p <> q then return p > q
    end for
    return false
end function

' The newer Roku release found by the last update check, or "" (registry "appLatest").
function availableUpdate(reg as object) as string
    if not reg.Exists("appLatest") then return ""
    latest = reg.Read("appLatest")
    if latest <> "" and isNewer(latest, appVersion()) then return latest
    return ""
end function

' Records a finished UpdateChecker's result ("error" is not recorded).
sub saveUpdateCheck(reg as object, checker as object)
    if checker.latest = "error" then return
    reg.Write("appLatest", checker.latest)
    reg.Write("appZipUrl", checker.zipUrl)
    reg.Write("appShaUrl", checker.shaUrl)
    reg.Write("appCheckedAt", CreateObject("roDateTime").AsSeconds().ToStr())
    reg.Flush()
end sub

function ago(seconds as integer) as string
    d = CreateObject("roDateTime").AsSeconds() - seconds
    if d < 60 then return "just now"
    if d < 3600 then return (d \ 60).ToStr() + " min ago"
    if d < 86400 then return (d \ 3600).ToStr() + " h ago"
    return (d \ 86400).ToStr() + " days ago"
end function
