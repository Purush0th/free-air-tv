sub init()
    m.top.functionName = "check"
end sub

sub check()
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl("https://api.github.com/repos/Purush0th/free-air-tv/releases?per_page=30")
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    ' GitHub's API refuses requests without a User-Agent.
    xfer.AddHeader("User-Agent", "FreeAirTV-Roku")
    xfer.AddHeader("Accept", "application/vnd.github+json")
    port = CreateObject("roMessagePort")
    xfer.SetMessagePort(port)
    latest = "error"
    if xfer.AsyncGetToString()
        msg = Wait(10000, port)
        if type(msg) = "roUrlEvent" and msg.GetResponseCode() = 200
            latest = ""
            releases = ParseJson(msg.GetString())
            if type(releases) = "roArray"
                ' Newest first; take the first published Roku release.
                for each r in releases
                    tag = r.tag_name
                    if GetInterface(tag, "ifString") <> invalid and Left(tag, 6) = "roku-v"
                        latest = Mid(tag, 7)
                        if type(r.assets) = "roArray"
                            for each a in r.assets
                                n = a.name
                                if GetInterface(n, "ifString") <> invalid
                                    if Right(n, 4) = ".zip" then m.top.zipUrl = a.browser_download_url
                                    if Right(n, 11) = ".zip.sha256" then m.top.shaUrl = a.browser_download_url
                                end if
                            end for
                        end if
                        exit for
                    end if
                end for
            end if
        else if msg = invalid
            xfer.AsyncCancel()
        end if
    end if
    m.top.latest = latest
end sub
