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

    bandwidthRe = CreateObject("roRegex", "[:,]BANDWIDTH=(\d+)", "")
    resolutionRe = CreateObject("roRegex", "RESOLUTION=(\d+)x(\d+)", "")
    byHeight = {}
    for each line in body.Tokenize(Chr(10))
        if Left(line, 18) = "#EXT-X-STREAM-INF:"
            b = bandwidthRe.Match(line)
            r = resolutionRe.Match(line)
            if b.Count() = 2 and r.Count() = 3
                h = r[2]
                bw = b[1].ToInt()
                if not byHeight.DoesExist(h) or byHeight[h] < bw then byHeight[h] = bw
            end if
        end if
    end for

    variants = []
    for each h in byHeight
        variants.Push({ height: h.ToInt(), bandwidth: byHeight[h] })
    end for
    variants.SortBy("height", "r")
    m.top.variants = variants
end sub
