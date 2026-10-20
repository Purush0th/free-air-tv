sub init()
    m.top.functionName = "check"
end sub

sub check()
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl("https://api.github.com/repos/Purush0th/free-air-tv/releases?per_page=100")
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
                ' GitHub doesn't list releases newest first (it gave roku-v0.1.9, 0.1.8, 0.1.10),
                ' so look at every Roku release and keep the highest version.
                best = invalid
                for each r in releases
                    tag = r.tag_name
                    if GetInterface(tag, "ifString") <> invalid and Left(tag, 6) = "roku-v"
                        version = Mid(tag, 7)
                        if best = invalid or isNewer(version, latest)
                            best = r
                            latest = version
                        end if
                    end if
                end for
                if best <> invalid and type(best.assets) = "roArray"
                    for each a in best.assets
                        n = a.name
                        if GetInterface(n, "ifString") <> invalid
                            if Right(n, 4) = ".zip" then m.top.zipUrl = a.browser_download_url
                            if Right(n, 11) = ".zip.sha256" then m.top.shaUrl = a.browser_download_url
                        end if
                    end for
                end if
            end if
        else if msg = invalid
            xfer.AsyncCancel()
        end if
    end if
    m.top.latest = latest
end sub
