sub init()
    m.top.functionName = "load"
end sub

sub load()
    body = ""
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl(m.top.url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.AddHeader("User-Agent", "FreeAirTV-Roku/" + appVersion())
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    if xfer.AsyncGetToString()
        msg = Wait(10000, port)
        if type(msg) = "roUrlEvent" and msg.GetResponseCode() = 200
            body = msg.GetString()
        else if msg = invalid
            xfer.AsyncCancel()
        end if
    end if
    m.top.body = body
end sub

function appVersion() as string
    info = CreateObject("roAppInfo")
    return info.GetVersion()
end function
