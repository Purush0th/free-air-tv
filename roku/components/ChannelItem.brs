sub init()
    m.num = m.top.findNode("num")
    m.name = m.top.findNode("name")
    m.locale = m.top.findNode("locale")
    m.dot = m.top.findNode("dot")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    ' Rows are recycled: follow the flags of the channel this row shows now.
    if m.content = invalid or not m.content.isSameNode(c)
        if m.content <> invalid
            m.content.unobserveFieldScoped("chPlaying")
        end if
        m.content = c
        c.observeFieldScoped("chPlaying", "onContent")
    end if
    m.num.text = c.chNum
    m.name.text = c.title
    m.locale.uri = c.chLocale
    m.dot.visible = c.chPlaying
    onFocus()
end sub

sub onFocus()
    ' The list only shows (and has focus) while the panel is open, so focus alone decides.
    selected = m.top.focusPercent > 0.5
    if selected
        m.num.color = "0x555555FF"
        m.name.color = "0x111111FF"
        m.locale.blendColor = "0x3D3D3DFF"
        m.dot.uri = "pkg:/images/dot_on_pill.png"
    else
        m.num.color = "0x9E9E9EFF"
        m.name.color = "0xFFFFFFFF"
        m.locale.blendColor = "0xB4B4B4FF"
        m.dot.uri = "pkg:/images/dot.png"
    end if
end sub
