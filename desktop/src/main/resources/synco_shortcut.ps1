param(
    [string]$TargetPath,
    [string]$WorkingDir
)

$ErrorActionPreference = "Stop"

try {
    $startMenu = [Environment]::GetFolderPath("Programs")
    $shortcutPath = Join-Path $startMenu "Synco.lnk"

    $WshShell = New-Object -ComObject WScript.Shell
    $Shortcut = $WshShell.CreateShortcut($shortcutPath)
    $Shortcut.TargetPath = $TargetPath
    $Shortcut.WorkingDirectory = $WorkingDir
    $Shortcut.Description = "Synco Desktop - Unified Remote Audio & System Sync"
    $Shortcut.Save()
} catch {
    # Non-fatal: the AWT tray balloon is the guaranteed fallback path.
}
