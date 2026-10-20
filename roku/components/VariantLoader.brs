sub init()
    m.top.functionName = "load"
end sub

sub load()
    body = ""
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl(m.top.url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.AddHeader("User-Agent", "FreeAirTV/" + CreateObject("roAppInfo").GetVersion())
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    if xfer.AsyncGetToString()
        msg = Wait(8000, port)
        if type(msg) = "roUrlEvent" and msg.GetResponseCode() = 200
            body = msg.GetString()
        else if msg = invalid
            xfer.AsyncCancel()
        end if
    end if

    ' Split the master playlist into its shared lines (header, EXT-X-MEDIA audio groups) and
    ' its variants (an EXT-X-STREAM-INF line plus the URI line after it).
    bandwidthRe = CreateObject("roRegex", "[:,]BANDWIDTH=(\d+)", "")
    resolutionRe = CreateObject("roRegex", "RESOLUTION=(\d+)x(\d+)", "")
    shared = []
    found = []
    info = ""
    for each raw in body.Tokenize(Chr(10))
        line = raw.Trim()
        if line = ""
            ' skip
        else if Left(line, 18) = "#EXT-X-STREAM-INF:"
            info = line
        else if info <> "" and Left(line, 1) <> "#"
            b = bandwidthRe.Match(info)
            r = resolutionRe.Match(info)
            if b.Count() = 2 and r.Count() = 3
                found.Push({ height: r[2].ToInt(), bandwidth: b[1].ToInt(), info: info, uri: absolute(line) })
            end if
            info = ""
        else if Left(line, 13) = "#EXT-X-MEDIA:"
            shared.Push(absoluteMediaUri(line))
        else if Left(line, 1) = "#" and Left(line, 22) <> "#EXT-X-I-FRAME-STREAM-"
            shared.Push(line)
        end if
    end for

    ' One variant per height (its highest bitrate), tallest first.
    byHeight = {}
    for each v in found
        k = v.height.ToStr()
        if not byHeight.DoesExist(k) or byHeight[k].bandwidth < v.bandwidth then byHeight[k] = v
    end for
    variants = []
    for each k in byHeight
        variants.Push(byHeight[k])
    end for
    variants.SortBy("height", "r")

    ' To pin a size, the player gets a copy of the master playlist that lists only that size:
    ' with nothing to switch to, adaptive streaming stays on it, and audio groups are kept.
    ' (Roku's MinBandwidth/MaxBandwidth hints made every pinned size fail with error -3.)
    out = []
    for each v in variants
        file = "tmp:/pin-" + m.top.channelId + "-" + v.height.ToStr() + ".m3u8"
        text = ""
        for each line in shared
            text = text + line + Chr(10)
        end for
        text = text + v.info + Chr(10) + v.uri + Chr(10)
        if not WriteAsciiFile(file, text) then file = ""
        out.Push({ height: v.height, bandwidth: v.bandwidth, pinUrl: file })
    end for
    m.top.variants = out
end sub

' A URI from the master playlist made absolute against the playlist's own URL.
function absolute(uri as string) as string
    if Left(uri, 7) = "http://" or Left(uri, 8) = "https://" then return uri
    base = m.top.url
    q = Instr(1, base, "?")
    if q > 0 then base = Left(base, q - 1)
    if Left(uri, 1) = "/"
        ' Scheme and host only.
        slash = Instr(9, base, "/")
        if slash > 0 then base = Left(base, slash - 1)
        return base + uri
    end if
    last = 0
    i = Instr(1, base, "/")
    while i > 0
        last = i
        i = Instr(i + 1, base, "/")
    end while
    return Left(base, last) + uri
end function

' An EXT-X-MEDIA line with its URI="..." made absolute.
function absoluteMediaUri(line as string) as string
    re = CreateObject("roRegex", "URI=" + Chr(34) + "([^" + Chr(34) + "]*)" + Chr(34), "")
    hit = re.Match(line)
    if hit.Count() < 2 then return line
    return re.Replace(line, "URI=" + Chr(34) + absolute(hit[1]) + Chr(34))
end function
