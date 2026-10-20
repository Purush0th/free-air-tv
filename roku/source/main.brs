' FreeAir TV for Roku: free-to-air Tamil live TV.
' Opens MainScene and keeps the channel running until the scene closes (Back with no panel open).
sub Main()
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.SetMessagePort(port)
    screen.CreateScene("MainScene")
    screen.Show()
    while true
        msg = Wait(0, port)
        if type(msg) = "roSGScreenEvent" and msg.IsScreenClosed() then return
    end while
end sub
