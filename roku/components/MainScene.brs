' FreeAir TV main scene.
'
' Channels come from channels.json on GitHub, with the last good copy kept in the registry
' and the copy built into the package as the fallback, so the channel starts playing at once
' and picks up list changes in the background. Channels with "isActive": false are skipped.
'
' Channels switched off in Settings (one by one, or a whole language) are left out of the
' channel list and of Up/Down, as on Fire TV. m.allChannels holds every active channel,
' m.channels the ones that are on; m.current indexes m.channels.
'
' Keys: Up/Down change channel; OK or Left opens the channel list; Right opens Picture & Sound
' (quality, mute, and the Settings button); Back closes a panel, or exits when none is open.
' Roku OS keeps * for its own options overlay while video plays, so * never reaches the
' channel; it opens Picture & Sound anyway in case a Roku delivers it.

function channelsUrl() as string
    return "https://raw.githubusercontent.com/Purush0th/free-air-tv/main/channels.json"
end function

sub init()
    m.video = m.top.findNode("video")
    m.keys = m.top.findNode("keys")
    m.list = m.top.findNode("list")
    m.guide = m.top.findNode("guide")
    m.card = m.top.findNode("card")
    m.cardBg = m.top.findNode("cardBg")
    m.cardNum = m.top.findNode("cardNum")
    m.cardName = m.top.findNode("cardName")
    m.cardLocale = m.top.findNode("cardLocale")
    m.cardRight = m.top.findNode("cardRight")
    m.cardState = m.top.findNode("cardState")
    m.cardClock = m.top.findNode("cardClock")
    m.liveDot = m.top.findNode("liveDot")
    m.status = m.top.findNode("status")
    m.cardTimer = m.top.findNode("cardTimer")
    m.retryTimer = m.top.findNode("retryTimer")
    m.options = m.top.findNode("options")
    m.optList = m.top.findNode("optList")
    m.settings = m.top.findNode("settings")
    m.loader = m.top.findNode("loader")
    m.loaderAnim = m.top.findNode("loaderAnim")
    m.loaderDelay = m.top.findNode("loaderDelay")

    m.reg = CreateObject("roRegistrySection", "FreeAirTV")
    m.hidden = loadIdSet(m.reg, "hidden")
    m.hiddenLangs = loadIdSet(m.reg, "hiddenLangs")
    m.allChannels = []
    m.channels = []
    m.fromGitHub = false
    m.current = -1
    m.retries = 0
    ' Quality: the tallest picture to play (0 = Auto), kept between launches like on Fire TV.
    m.quality = 0
    if m.reg.Exists("quality") then m.quality = m.reg.Read("quality").ToInt()
    m.variants = {}       ' channel id -> [{height, bandwidth}] from its master playlist
    m.playingHeight = 0   ' height of the picture playing now, 0 until known
    m.optRows = []

    m.video.observeField("state", "onVideoState")
    m.video.observeField("streamingSegment", "onSegment")
    m.cardLocale.observeField("loadStatus", "onCardLocaleLoaded")
    m.list.observeField("itemSelected", "onItemSelected")
    m.optList.observeField("itemSelected", "onOptionSelected")
    m.cardTimer.observeField("fire", "onCardTimer")
    m.retryTimer.observeField("fire", "onRetry")
    m.loaderDelay.observeField("fire", "showLoader")
    m.settings.observeField("closed", "onSettingsClosed")
    m.settings.observeField("refresh", "onRefreshRequested")

    ' Start with the cached or built-in list, then refresh from GitHub.
    json = ""
    if m.reg.Exists("channels") then json = m.reg.Read("channels")
    if setChannels(json)
        m.fromGitHub = true
    else
        setChannels(ReadAsciiFile("pkg:/channels.json"))
    end if
    startLast()
    fetchChannels(false)
    checkForUpdateNow()

    m.keys.setFocus(true)
end sub

' ---- Channel list ----

' Parses channels.json into m.allChannels (active channels only) and m.channels (those that
' are on). False if it holds no channel.
function setChannels(json as string) as boolean
    if json = "" then return false
    data = ParseJson(json)
    if type(data) <> "roArray" then return false
    list = []
    for each o in data
        if type(o) = "roAssociativeArray" and isString(o.id) and isString(o.url) and o.url <> ""
            if GetInterface(o.isActive, "ifBoolean") = invalid or o.isActive
                list.Push({
                    id: o.id,
                    name: textOf(o.name, o.id),
                    label22: labelUri(textOf(o.locale, ""), 22),
                    label26: labelUri(textOf(o.locale, ""), 26),
                    lang: textOf(o.lang, "ta-IN"),
                    url: o.url
                })
            end if
        end if
    end for
    if list.Count() = 0 then return false
    m.allChannels = list
    applyShown()
    return true
end function

' m.channels = the channels that are on, keeping m.current on the same channel if it still is.
sub applyShown()
    id = ""
    if m.current >= 0 and m.current < m.channels.Count() then id = m.channels[m.current].id
    shown = []
    for each ch in m.allChannels
        if not m.hidden.DoesExist(ch.id) and not m.hiddenLangs.DoesExist(ch.lang) then shown.Push(ch)
    end for
    m.channels = shown
    m.current = indexOf(id)
end sub

' Downloads channels.json; byUser = from Settings, which then shows how it went.
sub fetchChannels(byUser as boolean)
    m.fetchByUser = byUser
    m.channelTask = CreateObject("roSGNode", "ChannelLoader")
    m.channelTask.url = channelsUrl()
    m.channelTask.observeField("body", "onRemoteChannels")
    m.channelTask.control = "run"
end sub

sub onRemoteChannels()
    body = m.channelTask.body
    byUser = m.fetchByUser
    m.fetchByUser = false
    if body = "" or ParseJson(body) = invalid
        if byUser then m.settings.refreshStatus = "Couldn't reach GitHub. Check the connection and try again."
        return
    end if
    changed = not m.reg.Exists("channels") or m.reg.Read("channels") <> body
    m.fromGitHub = true
    m.reg.Write("fetchedAt", CreateObject("roDateTime").AsSeconds().ToStr())
    m.reg.Flush()
    if byUser
        if changed
            m.settings.refreshStatus = "Channel list updated."
        else
            m.settings.refreshStatus = "Channel list is up to date."
        end if
    end if
    if not changed
        m.settings.source = sourceText()
        return
    end if
    playingId = ""
    playingUrl = ""
    if m.current >= 0
        playingId = m.channels[m.current].id
        playingUrl = m.channels[m.current].url
    end if
    if not setChannels(body) then return
    ' Keep the copy for next launch (well under the 16 KB registry limit).
    if Len(body) < 15000
        m.reg.Write("channels", body)
        m.reg.Flush()
    end if
    updateSettingsInfo()
    if m.settings.visible then return  ' Settings restarts playback when it closes
    i = indexOf(playingId)
    if i >= 0
        m.current = i
        buildList()
        if m.channels[i].url <> playingUrl then play(i, false)
    else
        ' The channel playing now was removed: move to the first one.
        m.current = -1
        buildList()
        startLast()
    end if
end sub

function indexOf(id as string) as integer
    for i = 0 to m.channels.Count() - 1
        if m.channels[i].id = id then return i
    end for
    return -1
end function

sub buildList()
    root = CreateObject("roSGNode", "ContentNode")
    for i = 0 to m.channels.Count() - 1
        ch = m.channels[i]
        item = root.createChild("ContentNode")
        item.title = ch.name
        item.addFields({
            chNum: (i + 1).ToStr(),
            chLocale: ch.label22,
            chPlaying: (i = m.current)
        })
    end for
    focus = m.list.itemFocused
    m.list.content = root
    if focus >= 0 and focus < m.channels.Count() then m.list.jumpToItem = focus
end sub

' Refreshes one row's flags without rebuilding the list.
sub updateRow(i as integer)
    if m.list.content = invalid or i < 0 or i >= m.list.content.getChildCount() then return
    item = m.list.content.getChild(i)
    item.chPlaying = (i = m.current)
end sub

' ---- Playback ----

sub startLast()
    buildList()
    if m.channels.Count() = 0
        m.video.control = "stop"
        m.current = -1
        if m.allChannels.Count() = 0
            showStatus("No channels to show." + Chr(10) + "Check the internet connection.")
        else
            showStatus("No channels are on." + Chr(10) + "Press Right, then Settings, to turn some on.")
        end if
        return
    end if
    i = -1
    if m.reg.Exists("last") then i = indexOf(m.reg.Read("last"))
    if i < 0 then i = 0
    play(i, true)
end sub

sub play(i as integer, announce as boolean)
    if i < 0 or i >= m.channels.Count() then return
    previous = m.current
    m.current = i
    m.retries = 0
    m.retryTimer.control = "stop"
    m.status.visible = false
    ch = m.channels[i]

    content = CreateObject("roSGNode", "ContentNode")
    content.url = ch.url
    content.title = ch.name
    content.streamFormat = "hls"
    content.live = true
    m.cappedAt = bandwidthCap(ch.id)
    if m.cappedAt > 0 then content.MaxBandwidth = m.cappedAt
    m.playingHeight = 0
    m.video.control = "stop"
    m.video.content = content
    m.video.control = "play"
    if not m.variants.DoesExist(ch.id) then loadVariants(ch)

    m.reg.Write("last", ch.id)
    m.reg.Flush()
    updateRow(previous)
    updateRow(i)
    if announce then showCard()
end sub

' ---- Quality ----

sub loadVariants(ch as object)
    task = CreateObject("roSGNode", "VariantLoader")
    task.channelId = ch.id
    task.url = ch.url
    task.observeField("variants", "onVariants")
    task.control = "run"
    m.variantTask = task  ' keep it alive until it reports
end sub

sub onVariants(event as object)
    task = event.getRoSGNode()
    id = task.channelId
    m.variants[id] = event.getData()
    if m.current < 0 or m.channels[m.current].id <> id then return
    ' A quality was chosen before this channel's sizes were known: restart within it.
    if bandwidthCap(id) <> m.cappedAt then play(m.current, false)
    if m.options.visible then buildOptions()
end sub

' MaxBandwidth (kbps) for the chosen quality on this channel: its best picture no taller than
' the choice, or its smallest if all are taller. 0 = no limit.
function bandwidthCap(id as string) as integer
    if m.quality <= 0 or not m.variants.DoesExist(id) then return 0
    list = m.variants[id]
    if list.Count() = 0 then return 0
    pick = list[list.Count() - 1]
    for each v in list
        if v.height <= m.quality
            pick = v
            exit for
        end if
    end for
    if pick.height = list[0].height then return 0  ' the best on offer anyway
    return pick.bandwidth \ 1000 + 1
end function

' Height of the picture playing now, from the segment being downloaded.
sub onSegment()
    seg = m.video.streamingSegment
    if seg = invalid then return
    h = 0
    if seg.DoesExist("height") then h = seg.height
    if h = 0 and seg.DoesExist("segBitrateBps") and m.current >= 0
        ' No size reported: match the bitrate to the nearest variant.
        list = m.variants[m.channels[m.current].id]
        if list <> invalid
            best = 2147483647
            for each v in list
                d = Abs(v.bandwidth - seg.segBitrateBps)
                if d < best
                    best = d
                    h = v.height
                end if
            end for
        end if
    end if
    if h <= 0 or h = m.playingHeight then return
    m.playingHeight = h
    if m.video.state = "playing" then setCardState(liveText(), true)
    if m.options.visible then buildOptions()
end sub

function liveText() as string
    if m.playingHeight > 0 then return "LIVE · " + m.playingHeight.ToStr() + "p"
    return "LIVE"
end function

' ---- Picture & Sound panel (Right or *) ----

sub buildOptions()
    heights = []
    if m.current >= 0 and m.variants.DoesExist(m.channels[m.current].id)
        for each v in m.variants[m.channels[m.current].id]
            heights.Push(v.height)
        end for
    end if
    ' Keep the chosen quality listed even on a channel that lacks it.
    if m.quality > 0
        found = false
        for each h in heights
            if h = m.quality then found = true
        end for
        if not found
            heights.Push(m.quality)
            heights.Sort("r")
        end if
    end if

    m.optRows = [{ kind: "quality", height: 0 }]
    root = CreateObject("roSGNode", "ContentNode")
    autoSub = "Best for your connection"
    if m.quality = 0 and m.playingHeight > 0 then autoSub = "Playing at " + m.playingHeight.ToStr() + "p"
    addOption(root, "Auto", autoSub, m.quality = 0)
    for each h in heights
        m.optRows.Push({ kind: "quality", height: h })
        note = ""
        if h = m.quality and m.playingHeight > 0 and m.playingHeight <> h then note = "Not offered here; plays the nearest lower"
        addOption(root, h.ToStr() + "p", note, h = m.quality)
    end for
    m.optRows.Push({ kind: "mute" })
    muteSub = "Off"
    if m.video.mute then muteSub = "On"
    addOption(root, "Mute", muteSub, m.video.mute)
    ' The Settings button, as on the Fire TV guide header.
    m.optRows.Push({ kind: "settings" })
    item = addOption(root, "Settings", "Channels, languages, about", false)
    item.optIcon = "pkg:/images/gear.png"

    focus = m.optList.itemFocused
    m.optList.content = root
    if focus >= 0 and focus < m.optRows.Count() then m.optList.jumpToItem = focus
end sub

function addOption(root as object, title as string, subText as string, selected as boolean) as object
    item = root.createChild("ContentNode")
    item.title = title
    item.addFields({ optSub: subText, optSelected: selected, optIcon: "" })
    return item
end function

sub openOptions()
    m.card.visible = false
    m.optList.content = invalid
    buildOptions()
    ' Start on the quality in use.
    for i = 0 to m.optRows.Count() - 1
        if m.optRows[i].kind = "quality" and m.optRows[i].height = m.quality then m.optList.jumpToItem = i
    end for
    m.options.visible = true
    m.optList.setFocus(true)
end sub

sub closeOptions()
    m.options.visible = false
    m.keys.setFocus(true)
end sub

sub onOptionSelected()
    row = m.optRows[m.optList.itemSelected]
    if row.kind = "quality"
        if row.height <> m.quality
            m.quality = row.height
            m.reg.Write("quality", m.quality.ToStr())
            m.reg.Flush()
            play(m.current, false)
        end if
    else if row.kind = "mute"
        m.video.mute = not m.video.mute
    else if row.kind = "settings"
        closeOptions()
        openSettings()
        return
    end if
    buildOptions()
end sub

' ---- Settings ----

sub openSettings()
    ' Like the Fire TV Settings screen, it covers the picture, so playback stops meanwhile.
    m.video.control = "stop"
    hideLoader()
    m.card.visible = false
    m.status.visible = false
    m.settings.refreshStatus = ""
    updateSettingsInfo()
    m.settings.visible = true
    m.settings.callFunc("open", {})
end sub

sub updateSettingsInfo()
    m.settings.channels = m.allChannels
    m.settings.source = sourceText()
end sub

function sourceText() as string
    if not m.fromGitHub then return "Built-in list; replaced by the GitHub list once it loads."
    if not m.reg.Exists("fetchedAt") then return "From GitHub"
    return "From GitHub · checked " + ago(m.reg.Read("fetchedAt").ToInt())
end function

' Looks for a newer Roku release on GitHub at most every 12 hours; Settings shows the result.
sub checkForUpdateNow()
    if m.reg.Exists("appCheckedAt")
        if CreateObject("roDateTime").AsSeconds() - m.reg.Read("appCheckedAt").ToInt() < 12 * 3600 then return
    end if
    m.updateChecker = CreateObject("roSGNode", "UpdateChecker")
    m.updateChecker.observeField("latest", "onUpdateChecked")
    m.updateChecker.control = "run"
end sub

sub onUpdateChecked()
    saveUpdateCheck(m.reg, m.updateChecker)
end sub

sub onRefreshRequested()
    m.settings.refreshStatus = "Checking GitHub…"
    fetchChannels(true)
end sub

sub onSettingsClosed()
    m.settings.visible = false
    m.keys.setFocus(true)
    ' Pick up what was switched on or off, then carry on with the same channel if it is on.
    m.hidden = loadIdSet(m.reg, "hidden")
    m.hiddenLangs = loadIdSet(m.reg, "hiddenLangs")
    applyShown()
    if m.current >= 0
        buildList()
        play(m.current, true)
    else
        startLast()
    end if
end sub

' Next (+1) or previous (-1) channel that is on.
sub zap(delta as integer)
    n = m.channels.Count()
    if n = 0 then return
    i = m.current
    if i < 0 then i = 0
    play((i + delta + n) mod n, true)
end sub

sub onVideoState()
    state = m.video.state
    if state = "buffering"
        if not m.loader.visible then m.loaderDelay.control = "start"
    else
        hideLoader()
    end if
    if state = "playing"
        m.retries = 0
        setCardState(liveText(), true)
        ' The card stays up while the channel connects, then 4 s more once it plays.
        if m.card.visible
            m.cardTimer.control = "stop"
            m.cardTimer.control = "start"
        end if
    else if state = "buffering"
        setCardState("", false)  ' the loader shows the connecting
    else if state = "error" or state = "finished"
        ' Live streams drop now and then: retry a few times before giving up.
        print "FreeAir: "; state; " "; m.video.errorCode; " "; m.video.errorMsg
        if m.retries < 3
            m.retries = m.retries + 1
            setCardState("Reconnecting…", false)
            m.retryTimer.control = "start"
        else
            setCardState("Not available now", false)
        end if
        showCard()
        m.cardTimer.control = "stop"
    end if
end sub

sub onRetry()
    if m.current < 0 then return
    m.video.control = "stop"
    m.video.control = "play"
end sub

' ---- Connecting loader ----

sub showLoader()
    if m.video.state <> "buffering" or m.settings.visible or m.current < 0 then return
    label = m.top.findNode("loaderLabel")
    label.width = 0
    label.text = m.channels[m.current].name
    w = label.boundingRect().width
    if w > 240 then w = 240
    boxW = w + 48
    if boxW < 144 then boxW = 144
    label.width = boxW - 24
    label.translation = [12, 100]
    bg = m.top.findNode("loaderBg")
    bg.width = boxW
    bg.height = 148
    for each id in ["wave1", "wave2", "wave3", "loaderDot"]
        m.top.findNode(id).translation = [(boxW - 72) / 2, 20]
    end for
    m.loader.translation = [(1920 - boxW) / 2, (1080 - 148) / 2]
    m.loader.visible = true
    m.loaderAnim.control = "start"
end sub

sub hideLoader()
    m.loaderDelay.control = "stop"
    if not m.loader.visible then return
    m.loader.visible = false
    m.loaderAnim.control = "stop"
end sub

' ---- Now-watching card ----

sub showCard()
    if m.current < 0 then return
    ch = m.channels[m.current]
    m.cardNum.text = (m.current + 1).ToStr()
    fitLabel(m.cardName, ch.name, 520)
    m.cardLocale.uri = ch.label26
    now = CreateObject("roDateTime")
    now.ToLocalTime()
    m.cardClock.text = clockText(now)
    if m.video.state = "playing" then setCardState(liveText(), true) else setCardState("", false)
    layoutCard()
    m.card.visible = true
    m.cardTimer.control = "stop"
    if m.video.state = "playing" then m.cardTimer.control = "start"
end sub

sub onCardLocaleLoaded()
    if m.card.visible then layoutCard()
end sub

sub onCardTimer()
    if m.video.state = "playing" then m.card.visible = false
end sub

sub setCardState(text as string, live as boolean)
    m.cardState.text = text
    m.liveDot.visible = live
    if live
        m.cardState.color = "0xF2A93BFF"
        m.cardState.translation = [28, 26]
    else
        m.cardState.color = "0xB4B4B4FF"
        m.cardState.translation = [0, 26]
    end if
    if m.card.visible then layoutCard()
end sub

' Sizes a label to its text, ellipsised past maxWidth.
sub fitLabel(label as object, text as string, maxWidth as integer)
    label.width = 0
    label.text = text
    if label.boundingRect().width > maxWidth then label.width = maxWidth
end sub

sub layoutCard()
    m.cardState.width = 0
    m.cardClock.width = 0
    left = 100
    textWidth = m.cardName.boundingRect().width
    w = 0
    if m.cardLocale.uri <> "" and m.cardLocale.loadStatus = "ready" then w = m.cardLocale.bitmapWidth
    if w > textWidth then textWidth = w
    x = left + textWidth + 48
    m.cardRight.translation = [x, 0]
    stateWidth = m.cardState.translation[0] + m.cardState.boundingRect().width
    rightWidth = m.cardClock.boundingRect().width
    if stateWidth > rightWidth then rightWidth = stateWidth
    ' Right-align the clock and state under each other.
    m.cardClock.translation = [rightWidth - m.cardClock.boundingRect().width, 70]
    m.cardBg.width = x + rightWidth + 40
end sub

function clockText(t as object) as string
    h = t.GetHours()
    suffix = " AM"
    if h >= 12 then suffix = " PM"
    h = h mod 12
    if h = 0 then h = 12
    mins = t.GetMinutes().ToStr()
    if Len(mins) < 2 then mins = "0" + mins
    return h.ToStr() + ":" + mins + suffix
end function

' ---- Status message ----

sub showStatus(text as string)
    label = m.top.findNode("statusText")
    bg = m.top.findNode("statusBg")
    label.width = 880
    label.text = text
    h = label.boundingRect().height + 64
    label.height = h
    bg.width = 960
    bg.height = h
    label.translation = [40, 0]
    m.status.translation = [480, (1080 - h) / 2]
    m.status.visible = true
end sub

' ---- Channel list panel ----

sub openGuide()
    if m.channels.Count() = 0 then return
    m.card.visible = false
    m.guide.visible = true
    if m.current >= 0 then m.list.jumpToItem = m.current
    m.list.setFocus(true)
end sub

sub closeGuide()
    m.guide.visible = false
    m.keys.setFocus(true)
end sub

sub onItemSelected()
    i = m.list.itemSelected
    closeGuide()
    if i <> m.current then play(i, true) else showCard()
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    print "FreeAir key: "; key
    if m.settings.visible then return false  ' SettingsScreen handles its own keys
    if m.options.visible
        if key = "back" or key = "left" or key = "options"
            closeOptions()
            return true
        end if
        return false
    end if
    if m.guide.visible
        if key = "back" or key = "right"
            closeGuide()
            return true
        end if
        return false
    end if
    if key = "up"
        zap(1)
        return true
    else if key = "down"
        zap(-1)
        return true
    else if key = "OK" or key = "left"
        openGuide()
        return true
    else if key = "right" or key = "options"
        openOptions()
        return true
    end if
    ' Back with the list closed falls through, which exits the channel.
    return false
end function
