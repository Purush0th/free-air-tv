sub init()
    m.title = m.top.findNode("title")
    m.sub = m.top.findNode("sub")
    m.dot = m.top.findNode("dot")
    m.icon = m.top.findNode("icon")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.title.text = c.title
    m.sub.text = c.optSub
    ' Rows without a second line centre their title.
    if c.optSub = "" then m.title.translation = [28, 29] else m.title.translation = [28, 14]
    m.dot.visible = c.optSelected
    m.icon.uri = c.optIcon
    m.icon.visible = c.optIcon <> ""
    onFocus()
end sub

sub onFocus()
    if m.top.focusPercent > 0.5
        m.title.color = "0x111111FF"
        m.sub.color = "0x3D3D3DFF"
        m.dot.uri = "pkg:/images/dot_on_pill.png"
        m.icon.blendColor = "0x111111FF"
    else
        m.title.color = "0xFFFFFFFF"
        m.sub.color = "0xB4B4B4FF"
        m.dot.uri = "pkg:/images/dot.png"
        m.icon.blendColor = "0xB4B4B4FF"
    end if
end sub
