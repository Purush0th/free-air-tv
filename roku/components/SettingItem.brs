' Content fields (set by SettingsScreen): sKind "heading" | "row", title, sSub, sSubImage,
' sValue, sValueMuted, sCheck "" | "on" | "off" | "some", sDim, sPaneFocused, and sRev, which
' SettingsScreen bumps after changing any of them in place.

sub init()
    m.heading = m.top.findNode("heading")
    m.title = m.top.findNode("title")
    m.subImage = m.top.findNode("subImage")
    m.sub = m.top.findNode("sub")
    m.value = m.top.findNode("value")
    m.check = m.top.findNode("check")
    m.subImage.observeField("loadStatus", "layout")
    m.large = invalid
end sub

function font(size as integer) as object
    f = CreateObject("roSGNode", "Font")
    f.uri = "font:SystemFontFile"
    f.size = size
    return f
end function

sub onContent()
    c = m.top.itemContent
    if c = invalid or m.top.width = 0 then return
    ' Rows are recycled: follow the content this row shows now.
    if m.content = invalid or not m.content.isSameNode(c)
        if m.content <> invalid then m.content.unobserveFieldScoped("sRev")
        m.content = c
        c.observeFieldScoped("sRev", "render")
    end if
    ' Left pane rows are large, right pane rows 30% smaller, as on Fire TV.
    large = m.top.width < 700
    if m.large = invalid or m.large <> large
        m.large = large
        if large
            m.s = { pad: 32, title: 36, sub: 26, check: 40, heading: 24 }
        else
            m.s = { pad: 28, title: 24, sub: 20, check: 28, heading: 20 }
        end if
        m.title.font = font(m.s.title)
        m.sub.font = font(m.s.sub)
        m.value.font = font(m.s.sub)
        m.heading.font = font(m.s.heading)
    end if
    render()
end sub

sub render()
    c = m.top.itemContent
    if c = invalid or m.s = invalid then return
    isHeading = c.sKind = "heading"
    m.heading.visible = isHeading
    m.title.visible = not isHeading
    m.sub.visible = not isHeading
    m.subImage.visible = not isHeading
    m.value.visible = not isHeading
    if isHeading
        m.heading.text = UCase(c.title)
        m.check.visible = false
        layout()
        return
    end if
    m.title.text = c.title
    m.sub.text = c.sSub
    if m.subImage.uri <> c.sSubImage then m.subImage.uri = c.sSubImage
    m.value.text = c.sValue
    m.check.visible = c.sCheck <> ""
    if c.sDim then m.top.opacity = 0.4 else m.top.opacity = 1.0

    selected = m.top.focusPercent > 0.5 and c.sPaneFocused
    suffix = ""
    if selected
        suffix = "_sel"
        m.title.color = "0x111111FF"
        m.sub.color = "0x3D3D3DFF"
        m.subImage.blendColor = "0x3D3D3DFF"
        if c.sValueMuted then m.value.color = "0x555555FF" else m.value.color = "0x8A5200FF"
    else
        m.title.color = "0xFFFFFFFF"
        m.sub.color = "0xB4B4B4FF"
        m.subImage.blendColor = "0xB4B4B4FF"
        if c.sValueMuted then m.value.color = "0xB4B4B4FF" else m.value.color = "0xF2A93BFF"
    end if
    if c.sCheck <> "" then m.check.uri = "pkg:/images/check_" + c.sCheck + suffix + ".png"
    layout()
end sub

sub layout()
    c = m.top.itemContent
    if c = invalid or m.s = invalid then return
    s = m.s
    w = m.top.width
    h = m.top.height
    if c.sKind = "heading"
        m.heading.translation = [s.pad, h - s.heading * 1.7]
        m.heading.width = w - 2 * s.pad
        return
    end if
    right = w - s.pad
    if c.sCheck <> ""
        m.check.width = s.check
        m.check.height = s.check
        m.check.translation = [right - s.check, (h - s.check) / 2]
        right = right - s.check - 24
    end if
    m.value.width = 0
    vw = 0
    if c.sValue <> ""
        vw = m.value.boundingRect().width
        m.value.translation = [right - vw, 0]
        m.value.height = h
        m.value.vertAlign = "center"
        right = right - vw - 24
    end if
    textWidth = right - s.pad
    m.title.width = textWidth
    lineH = s.title * 1.25
    subH = s.sub * 1.4
    hasSub = c.sSub <> "" or c.sSubImage <> ""
    if hasSub
        top = (h - lineH - subH) / 2
        m.title.translation = [s.pad, top]
        m.title.height = lineH
        subY = top + lineH
        x = s.pad
        if c.sSubImage <> "" and m.subImage.loadStatus = "ready"
            ' The image's text is centred in a box 1.65x its font size.
            m.subImage.translation = [x, subY + (subH - m.subImage.bitmapHeight) / 2]
            x = x + m.subImage.bitmapWidth
        end if
        m.sub.translation = [x, subY]
        m.sub.height = subH
        m.sub.vertAlign = "center"
        m.sub.width = right - x
    else
        m.title.translation = [s.pad, 0]
        m.title.height = h
    end if
    m.title.vertAlign = "center"
end sub
