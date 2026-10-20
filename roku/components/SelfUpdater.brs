sub init()
    m.top.functionName = "install"
end sub

function https(url as string) as object
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl(url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.AddHeader("User-Agent", "FreeAirTV-Roku")
    return xfer
end function

' Runs a transfer started on port and returns its event, or invalid after timeout seconds.
function waitFor(xfer as object, port as object, timeout as integer) as dynamic
    msg = Wait(timeout * 1000, port)
    if type(msg) = "roUrlEvent" then return msg
    xfer.AsyncCancel()
    return invalid
end function

sub install()
    zip = "tmp:/update.zip"
    body = "tmp:/update-body.bin"
    port = CreateObject("roMessagePort")

    m.top.status = "downloading"
    xfer = https(m.top.zipUrl)
    xfer.SetMessagePort(port)
    xfer.AsyncGetToFile(zip)
    msg = waitFor(xfer, port, 120)
    if msg = invalid or msg.GetResponseCode() <> 200
        m.top.status = "error: the download from GitHub failed"
        return
    end if

    data = CreateObject("roByteArray")
    data.ReadFile(zip)
    if m.top.shaUrl <> ""
        sumXfer = https(m.top.shaUrl)
        ' sha256sum's format: "<hex>  <file name>".
        parts = sumXfer.GetToString().Trim().Tokenize(" ")
        digest = CreateObject("roEVPDigest")
        digest.Setup("sha256")
        if parts.Count() = 0 or LCase(digest.Process(data)) <> LCase(parts[0])
            m.top.status = "error: the download didn't match its checksum"
            return
        end if
    end if

    ' The installer's upload form, as multipart/form-data.
    boundary = "----FreeAirTV" + CreateObject("roDeviceInfo").GetRandomUUID().Replace("-", "")
    crlf = Chr(13) + Chr(10)
    head = CreateObject("roByteArray")
    head.FromAsciiString("--" + boundary + crlf + "Content-Disposition: form-data; name=" + Chr(34) + "mysubmit" + Chr(34) + crlf + crlf + "Install" + crlf + "--" + boundary + crlf + "Content-Disposition: form-data; name=" + Chr(34) + "archive" + Chr(34) + "; filename=" + Chr(34) + "update.zip" + Chr(34) + crlf + "Content-Type: application/zip" + crlf + crlf)
    tail = CreateObject("roByteArray")
    tail.FromAsciiString(crlf + "--" + boundary + "--" + crlf)
    head.WriteFile(body)
    data.AppendFile(body)
    tail.AppendFile(body)

    m.top.status = "installing"
    ip = "127.0.0.1"
    for each name in CreateObject("roDeviceInfo").GetIPAddrs()
        ip = CreateObject("roDeviceInfo").GetIPAddrs()[name]
        exit for
    end for
    post = CreateObject("roUrlTransfer")
    post.SetUrl("http://" + ip + "/plugin_install")
    post.SetUserAndPassword("rokudev", m.top.password)
    post.AddHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
    post.SetMessagePort(port)
    post.AsyncPostFromFile(body)
    msg = waitFor(post, port, 120)
    ' If the install went ahead, this copy of the channel is usually closed before here.
    if msg = invalid
        m.top.status = "error: the Roku's installer didn't answer"
    else if msg.GetResponseCode() = 401
        m.top.status = "badpassword"
    else if msg.GetResponseCode() = 200
        page = msg.GetString()
        if Instr(1, page, "Install Success") > 0 or Instr(1, page, "Identical to previous") > 0
            m.top.status = "done"
        else
            m.top.status = "error: the Roku's installer didn't accept the package"
        end if
    else
        m.top.status = "error: the Roku's installer answered " + msg.GetResponseCode().ToStr()
    end if
end sub
