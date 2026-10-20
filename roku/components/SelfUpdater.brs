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

sub report(status as string)
    print "FreeAir update: "; status
    m.top.status = status
end sub

sub install()
    zip = "tmp:/update.zip"
    body = "tmp:/update-body.bin"
    port = CreateObject("roMessagePort")

    report("downloading")
    xfer = https(m.top.zipUrl)
    xfer.SetMessagePort(port)
    xfer.AsyncGetToFile(zip)
    msg = waitFor(xfer, port, 120)
    if msg = invalid
        report("error: the download from GitHub timed out")
        return
    else if msg.GetResponseCode() <> 200
        report("error: the download from GitHub failed, " + msg.GetResponseCode().ToStr() + " " + msg.GetFailureReason())
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
            report("error: the download didn't match its checksum")
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

    report("installing")
    ip = "127.0.0.1"
    for each name in CreateObject("roDeviceInfo").GetIPAddrs()
        ip = CreateObject("roDeviceInfo").GetIPAddrs()[name]
        exit for
    end for
    post = CreateObject("roUrlTransfer")
    post.SetUrl("http://" + ip + "/plugin_install")
    post.SetUserAndPassword("rokudev", m.top.password)
    post.SetMessagePort(port)
    ' The installer uses digest authentication. Sent cold, the upload goes out without the
    ' password, the installer answers 401 part-way and drops the connection, which can surface
    ' as error -55 (failed sending data). So authenticate with a small request first; the same
    ' transfer then sends the upload already signed. Network errors are retried.
    post.AsyncGetToString()
    msg = waitFor(post, port, 20)
    if msg <> invalid and msg.GetResponseCode() = 401
        report("badpassword")
        return
    end if
    post.AddHeader("Content-Type", "multipart/form-data; boundary=" + boundary)
    for attempt = 1 to 3
        post.AsyncPostFromFile(body)
        msg = waitFor(post, port, 120)
        if msg = invalid or msg.GetResponseCode() >= 0 then exit for
        print "FreeAir update: upload attempt "; attempt; " failed: "; msg.GetResponseCode(); " "; msg.GetFailureReason()
        Sleep(2000)
    end for
    ' If the install went ahead, this copy of the channel is usually closed before here.
    if msg = invalid
        report("error: the Roku's installer didn't answer")
    else if msg.GetResponseCode() = 401
        report("badpassword")
    else if msg.GetResponseCode() = 200
        page = msg.GetString()
        if Instr(1, page, "Install Success") > 0 or Instr(1, page, "Identical to previous") > 0
            report("done")
        else
            report("error: the Roku's installer didn't accept the package")
        end if
    else
        report("error: the Roku's installer answered " + msg.GetResponseCode().ToStr() + " " + msg.GetFailureReason())
    end if
end sub
