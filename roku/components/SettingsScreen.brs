' Settings: which languages and channels are on, the channel list's source, and About.
' Switches are saved straight to the registry ("hidden" channel ids, "hiddenLangs" language
' codes); MainScene reloads them when Settings closes.

sub init()
    m.navGroup = m.top.findNode("navGroup")
    m.navPill = m.top.findNode("navPill")
    m.navKeys = m.top.findNode("navKeys")
    m.detail = m.top.findNode("detail")
    m.detailTitle = m.top.findNode("detailTitle")
    m.detailTitleImage = m.top.findNode("detailTitleImage")
    m.detailSub = m.top.findNode("detailSub")
    m.reg = CreateObject("roRegistrySection", "FreeAirTV")
    m.english = ParseJson(ReadAsciiFile("pkg:/images/labels/languages.json"))
    if m.english = invalid then m.english = {}
    m.navRows = []
    m.detailRows = []
    m.activeNav = -1
    m.detailLast = 0
    m.inDetail = false
    m.rev = 0
    m.appStatus = ""
    m.detailFor = -1

    m.detail.observeField("itemFocused", "onDetailFocused")
    m.detail.observeField("itemSelected", "onDetailSelected")
    m.detailTitleImage.observeField("loadStatus", "layoutTitle")
end sub

' Called by MainScene each time Settings opens.
sub open(params as object)
    m.hidden = loadIdSet(m.reg, "hidden")
    m.hiddenLangs = loadIdSet(m.reg, "hiddenLangs")
    m.inDetail = false
    m.activeNav = -1
    m.detailFor = -1
    if m.updater = invalid then m.appStatus = ""
    buildNav()
    m.navKeys.setFocus(true)
    setPaneFocus()
end sub

sub onChannels()
    if m.top.visible and m.hidden <> invalid then buildNav()
end sub

sub onInfo()
    if m.hidden <> invalid then refreshRows()
end sub

' ---- Data ----

function languages() as object
    langs = []
    seen = {}
    for each ch in m.top.channels
        if not seen.DoesExist(ch.lang)
            seen[ch.lang] = true
            langs.Push(ch.lang)
        end if
    end for
    return langs
end function

function channelsIn(lang as string) as object
    list = []
    for each ch in m.top.channels
        if ch.lang = lang then list.Push(ch)
    end for
    return list
end function

function shownCount(list as object) as integer
    n = 0
    for each ch in list
        if not m.hidden.DoesExist(ch.id) then n = n + 1
    end for
    return n
end function

function englishName(lang as string) as string
    if m.english.DoesExist(lang) then return m.english[lang]
    return lang
end function

' ---- Panes ----

function newRow(root as object, kind as string, data as object) as object
    item = root.createChild("ContentNode")
    item.addFields({ sKind: kind, sSub: "", sSubImage: "", sValue: "", sValueMuted: false,
        sCheck: "", sDim: false, sPaneFocused: false, sRev: 0 })
    data.node = item
    data.kind = kind
    return data
end function

sub buildNav()
    root = CreateObject("roSGNode", "ContentNode")
    rows = []
    rows.Push(newRow(root, "heading", { title: "Channels" }))
    for each lang in languages()
        rows.Push(newRow(root, "lang", { lang: lang }))
    end for
    rows.Push(newRow(root, "heading", { title: "App" }))
    rows.Push(newRow(root, "updates", {}))
    rows.Push(newRow(root, "about", {}))
    m.navRows = rows
    ' Lay the pane out: headings are plain labels, rows are SettingItems.
    m.navGroup.removeChildrenIndex(m.navGroup.getChildCount(), 0)
    y = 0
    for each r in rows
        if r.DoesExist("title") then r.node.title = r.title
        if r.kind = "heading"
            r.node.sKind = "heading"
            label = m.navGroup.createChild("Label")
            f = CreateObject("roSGNode", "Font")
            f.uri = "font:SystemFontFile"
            f.size = 24
            label.font = f
            label.color = "0x9E9E9EFF"
            label.text = UCase(r.title)
            label.translation = [32, y + 14]
            y = y + 56
        else
            r.node.sKind = "row"
            item = m.navGroup.createChild("SettingItem")
            item.width = 490
            item.height = 104
            item.translation = [0, y]
            item.itemContent = r.node
            r.item = item
            r.y = y
            y = y + 112
        end if
    end for
    keep = m.activeNav
    if keep < 1 or keep >= rows.Count() or rows[keep].kind = "heading" then keep = 1
    m.activeNav = keep
    buildDetail()
    refreshRows()
end sub

' Shows the highlight on the left pane's active row.
sub placeNavPill()
    r = m.navRows[m.activeNav]
    for each row in m.navRows
        if row.DoesExist("item")
            if row.item.isSameNode(r.item) then row.item.focusPercent = 1 else row.item.focusPercent = 0
        end if
    end for
    m.navPill.translation = [112, 160 + r.y]
    if m.inDetail then m.navPill.uri = "pkg:/images/pill_grey.9.png" else m.navPill.uri = "pkg:/images/pill.9.png"
    m.navPill.visible = true
end sub

' Up (-1) or Down (+1) on the left pane, over the headings.
sub moveNav(dir as integer)
    j = m.activeNav + dir
    while j >= 0 and j < m.navRows.Count()
        if m.navRows[j].kind <> "heading"
            m.activeNav = j
            buildDetail()
            refreshRows()
            return
        end if
        j = j + dir
    end while
end sub

sub buildDetail()
    root = CreateObject("roSGNode", "ContentNode")
    rows = []
    item = m.navRows[m.activeNav]
    if item.kind = "lang"
        rows.Push(newRow(root, "all", { lang: item.lang }))
        for each ch in channelsIn(item.lang)
            rows.Push(newRow(root, "channel", { lang: item.lang, ch: ch }))
        end for
    else if item.kind = "updates"
        rows.Push(newRow(root, "appupdate", {}))
        rows.Push(newRow(root, "refresh", {}))
    else if item.kind = "about"
        langs = languages().Count()
        total = m.top.channels.Count()
        what = total.ToStr() + " channels in " + langs.ToStr() + " language"
        if langs <> 1 then what = what + "s"
        rows.Push(newRow(root, "info", { title: "Version", value: appVersion() }))
        rows.Push(newRow(root, "info", { title: "Channels", value: what }))
        rows.Push(newRow(root, "info", { title: "Source code", value: "github.com/Purush0th/free-air-tv" }))
    end if
    for each r in rows
        r.node.sKind = "row"
        if r.DoesExist("title") then r.node.title = r.title
        if r.DoesExist("value")
            r.node.sValue = r.value
            r.node.sValueMuted = true
        end if
    end for
    sameItem = m.detailFor = m.activeNav
    m.detailFor = m.activeNav
    m.detailRows = rows
    m.detail.content = root
    ' Rebuilt for the same left item (say, after a channel-list check): keep the focused row.
    if not (sameItem and m.detailLast < rows.Count() and enabled(rows[m.detailLast]))
        m.detailLast = firstEnabled(m.detailRows)
        if m.detailLast < 0 then m.detailLast = 0
    end if
    m.detail.jumpToItem = m.detailLast
end sub

' Fills in the text and ticks that change (counts, ticks, status lines) on both panes.
sub refreshRows()
    if m.navRows.Count() = 0 then return
    for each r in m.navRows
        n = r.node
        if r.kind = "lang"
            list = channelsIn(r.lang)
            off = m.hiddenLangs.DoesExist(r.lang)
            count = shownCount(list)
            if off then count = 0
            n.title = englishName(r.lang)
            n.sSubImage = langLabelUri(r.lang, 26)
            n.sSub = " · " + count.ToStr() + " of " + list.Count().ToStr() + " on"
            if off then n.sCheck = "off" else n.sCheck = "on"
        else if r.kind = "updates"
            n.title = "Updates"
            n.sSub = "Version " + appVersion()
            if availableUpdate(m.reg) <> "" then n.sValue = "New" else n.sValue = ""
        else if r.kind = "about"
            n.title = "About"
        end if
    end for

    item = m.navRows[m.activeNav]
    m.detailTitleImage.uri = ""
    if item.kind = "lang"
        list = channelsIn(item.lang)
        m.detailTitle.text = englishName(item.lang) + "  ·  "
        m.detailTitleImage.uri = langLabelUri(item.lang, 26)
        if m.hiddenLangs.DoesExist(item.lang)
            m.detailSub.text = englishName(item.lang) + " is off. Tick All Channels to turn it on."
        else
            m.detailSub.text = shownCount(list).ToStr() + " of " + list.Count().ToStr() + " channels on"
        end if
    else if item.kind = "updates"
        m.detailTitle.text = "Updates"
        m.detailSub.text = ""
    else
        m.detailTitle.text = "About"
        m.detailSub.text = ""
    end if
    layoutTitle()

    for each r in m.detailRows
        n = r.node
        if r.kind = "all"
            list = channelsIn(r.lang)
            count = shownCount(list)
            if m.hiddenLangs.DoesExist(r.lang) then count = 0
            n.title = "All Channels"
            if count = list.Count()
                n.sCheck = "on"
            else if count = 0
                n.sCheck = "off"
            else
                n.sCheck = "some"
            end if
        else if r.kind = "channel"
            n.title = r.ch.name
            n.sSubImage = r.ch.label22
            if m.hidden.DoesExist(r.ch.id) then n.sCheck = "off" else n.sCheck = "on"
            ' A switched-off language greys its channels; they keep their own ticks.
            n.sDim = m.hiddenLangs.DoesExist(r.lang)
        else if r.kind = "appupdate"
            latest = availableUpdate(m.reg)
            if latest <> ""
                n.title = "Install Version " + latest
                n.sValue = "New"
            else
                n.title = "Check for App Update"
                n.sValue = ""
            end if
            if m.appStatus <> ""
                n.sSub = m.appStatus
            else if latest <> ""
                n.sSub = "Downloads it from GitHub and installs it; FreeAir TV then restarts"
            else if m.reg.Exists("appCheckedAt")
                n.sSub = "Version " + appVersion() + " · checked " + ago(m.reg.Read("appCheckedAt").ToInt())
            else
                n.sSub = "Version " + appVersion() + " · not checked yet"
            end if
        else if r.kind = "refresh"
            n.title = "Check for Channel Updates"
            if m.top.refreshStatus <> "" then n.sSub = m.top.refreshStatus else n.sSub = m.top.source
        end if
    end for
    bump()
end sub

sub layoutTitle()
    m.detailTitle.width = 0
    w = m.detailTitle.boundingRect().width
    img = m.detailTitleImage
    img.translation = [28 + w, (44 - img.bitmapHeight) / 2]
end sub

' Tells every row to redraw, with which pane has focus.
sub bump()
    if m.navRows.Count() > 0 and m.activeNav >= 0 then placeNavPill()
    m.rev = m.rev + 1
    for each r in m.navRows
        r.node.sPaneFocused = not m.inDetail
        r.node.sRev = m.rev
    end for
    for each r in m.detailRows
        r.node.sPaneFocused = m.inDetail
        r.node.sRev = m.rev
    end for
end sub

sub setPaneFocus()
    bump()
end sub

' ---- Focus: headings and info rows are skipped ----

function enabled(r as object) as boolean
    if r.kind = "heading" or r.kind = "info" then return false
    if r.kind = "channel" then return not m.hiddenLangs.DoesExist(r.lang)
    return true
end function

function firstEnabled(rows as object) as integer
    for i = 0 to rows.Count() - 1
        if enabled(rows[i]) then return i
    end for
    return -1
end function

' The next row that can take focus from i in direction dir (+1/-1), or -1.
function nextEnabled(rows as object, i as integer, dir as integer) as integer
    j = i
    while j >= 0 and j < rows.Count()
        if enabled(rows[j]) then return j
        j = j + dir
    end while
    return -1
end function

function settle(list as object, rows as object, last as integer) as integer
    i = list.itemFocused
    if i < 0 or i >= rows.Count() then return last
    if enabled(rows[i]) then return i
    dir = 1
    if i < last then dir = -1
    j = nextEnabled(rows, i, dir)
    if j < 0 then j = last
    list.jumpToItem = j
    return j
end function

sub onDetailFocused()
    if m.detailRows.Count() = 0 then return
    m.detailLast = settle(m.detail, m.detailRows, m.detailLast)
end sub

' Moves focus into the right pane, onto its first row that can be acted on.
sub enterDetail()
    i = firstEnabled(m.detailRows)
    if i < 0 then return  ' nothing to act on there; keep focus here
    if enabled(m.detailRows[m.detailLast]) then i = m.detailLast
    m.detail.jumpToItem = i
    m.inDetail = true
    m.detail.setFocus(true)
    setPaneFocus()
end sub

sub leaveDetail()
    m.inDetail = false
    m.navKeys.setFocus(true)
    setPaneFocus()
end sub

' ---- Actions ----

sub onNavSelected()
    r = m.navRows[m.activeNav]
    if r.kind = "lang"
        if m.hiddenLangs.DoesExist(r.lang) then m.hiddenLangs.Delete(r.lang) else m.hiddenLangs[r.lang] = true
        saveIdSet(m.reg, "hiddenLangs", m.hiddenLangs)
        refreshRows()
    else if r.kind = "updates"
        enterDetail()
    end if
end sub

sub onDetailSelected()
    r = m.detailRows[m.detail.itemSelected]
    if not enabled(r) then return
    if r.kind = "channel"
        if m.hidden.DoesExist(r.ch.id) then m.hidden.Delete(r.ch.id) else m.hidden[r.ch.id] = true
        saveIdSet(m.reg, "hidden", m.hidden)
    else if r.kind = "all"
        ' All on: switch every channel off. Some or none on: switch them all on, the
        ' language included.
        list = channelsIn(r.lang)
        allOn = not m.hiddenLangs.DoesExist(r.lang) and shownCount(list) = list.Count()
        for each ch in list
            if allOn then m.hidden[ch.id] = true else m.hidden.Delete(ch.id)
        end for
        if not allOn and m.hiddenLangs.DoesExist(r.lang)
            m.hiddenLangs.Delete(r.lang)
            saveIdSet(m.reg, "hiddenLangs", m.hiddenLangs)
        end if
        saveIdSet(m.reg, "hidden", m.hidden)
    else if r.kind = "refresh"
        m.top.refresh = true
        return
    else if r.kind = "appupdate"
        if m.checker <> invalid or m.updater <> invalid then return  ' already busy
        if availableUpdate(m.reg) <> ""
            startInstall()
            return
        end if
        m.appStatus = "Checking GitHub…"
        refreshRows()
        m.checker = CreateObject("roSGNode", "UpdateChecker")
        m.checker.observeField("latest", "onAppChecked")
        m.checker.control = "run"
        return
    end if
    refreshRows()
end sub

sub onAppChecked()
    latest = m.checker.latest
    saveUpdateCheck(m.reg, m.checker)
    m.checker = invalid
    if latest = "error"
        m.appStatus = "Couldn't reach GitHub. Check the connection and try again."
    else if availableUpdate(m.reg) = ""
        m.appStatus = "You're up to date (version " + appVersion() + ")."
    else
        m.appStatus = ""
    m.detailFor = -1
    end if
    refreshRows()
end sub

' ---- Installing an update from the app ----

' Installing goes through the Roku's developer installer, which needs the developer password
' (set when developer mode was turned on). It is asked for once and kept in the registry.
sub startInstall()
    if m.reg.Exists("devPassword") and m.reg.Read("devPassword") <> ""
        runInstall(m.reg.Read("devPassword"))
        return
    end if
    dlg = CreateObject("roSGNode", "StandardKeyboardDialog")
    dlg.title = "Roku developer password"
    dlg.message = ["The password you set when you turned on developer mode (user rokudev). FreeAir TV keeps it on this Roku to install updates."]
    dlg.buttons = ["Install", "Cancel"]
    if dlg.textEditBox <> invalid then dlg.textEditBox.secureMode = true
    dlg.observeField("buttonSelected", "onPasswordDialog")
    m.dialog = dlg
    m.top.getScene().dialog = dlg
end sub

sub onPasswordDialog()
    dlg = m.dialog
    password = dlg.text
    install = dlg.buttonSelected = 0
    dlg.close = true
    m.dialog = invalid
    m.detail.setFocus(true)
    if install and password <> ""
        m.reg.Write("devPassword", password)
        m.reg.Flush()
        runInstall(password)
    end if
end sub

sub runInstall(password as string)
    m.updater = CreateObject("roSGNode", "SelfUpdater")
    m.updater.zipUrl = m.reg.Read("appZipUrl")
    m.updater.shaUrl = m.reg.Read("appShaUrl")
    m.updater.password = password
    m.updater.observeField("status", "onInstallStatus")
    m.updater.control = "run"
end sub

sub onInstallStatus()
    s = m.updater.status
    if s = "downloading"
        m.appStatus = "Downloading version " + availableUpdate(m.reg) + "…"
    else if s = "installing"
        m.appStatus = "Installing… FreeAir TV will restart."
    else if s = "done"
        m.appStatus = "Installed. FreeAir TV is restarting."
    else
        if s = "badpassword"
            m.reg.Delete("devPassword")
            m.reg.Flush()
            m.appStatus = "The Roku refused the password. Select Install to enter it again."
        else
            m.appStatus = "Update failed (" + Mid(s, 8) + "). Try again."
        end if
        m.updater = invalid
    end if
    refreshRows()
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if m.inDetail
        if key = "left" or key = "back"
            leaveDetail()
            return true
        end if
        return key = "right"
    end if
    if key = "up" or key = "down"
        if key = "up" then moveNav(-1) else moveNav(1)
        return true
    else if key = "OK"
        onNavSelected()
        return true
    else if key = "right"
        enterDetail()
        return true
    else if key = "back"
        m.top.closed = true
        return true
    end if
    return key = "left"
end function
