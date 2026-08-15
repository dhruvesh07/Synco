param(
    [string]$Title = "Synco",
    [string]$Text = ""
)

$ErrorActionPreference = "Stop"

try {
    $Aumid = "com.remoteaudiosync.synco"
    [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
    [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null

    $Template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02)
    $TextNodes = $Template.GetElementsByTagName("text")
    $TextNodes.Item(0).AppendChild($Template.CreateTextNode($Title)) | Out-Null
    $TextNodes.Item(1).AppendChild($Template.CreateTextNode($Text)) | Out-Null

    $Toast = [Windows.UI.Notifications.ToastNotification]::new($Template)
    $Notifier = [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($Aumid)
    $Notifier.Show($Toast)

    # Signal success so the caller can distinguish a real toast from a silent drop.
    Write-Output "TOAST_OK"
} catch {
    Write-Output ("TOAST_FAIL: " + $_.Exception.Message)
}
