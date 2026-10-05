# Enumerate every accessibility window and report where the nodes actually are.
#
# Motivated by a real capture in which a splash-ad window appeared to contain only
# nine nodes and reported its activity as `android.widget.FrameLayout`. An ad surface
# that small is suspicious: splash ads commonly live in their own window
# (TYPE_APPLICATION_OVERLAY, or a dedicated WebView window) rather than inside the
# activity's main window. If the reader only ever looks at `rootInActiveWindow`, it can
# miss the entire ad and every conclusion drawn from it is unsound.
#
# This script answers one question: **is the ad in a window we are not reading?**

param(
    [Parameter(Mandatory = $true)][string]$TargetPackage,
    [string]$Adb = 'F:\ASSDK\platform-tools\adb.exe',
    [int]$Seconds = 12,
    [int]$IntervalMs = 600
)

$ErrorActionPreference = 'Continue'

Write-Host "=== cold-launching $TargetPackage, sampling windows every ${IntervalMs}ms ==="
& $Adb shell am force-stop $TargetPackage | Out-Null
Start-Sleep -Milliseconds 700
& $Adb shell monkey -p $TargetPackage -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null

$start = Get-Date
$samples = @()

while (((Get-Date) - $start).TotalSeconds -lt $Seconds) {
    $elapsed = [int]((Get-Date) - $start).TotalMilliseconds

    # `dumpsys window windows` prints one block per window. We want the window list,
    # not the current focus: the ad may be a window that is visible but not focused.
    $win = (& $Adb shell dumpsys window windows 2>&1)

    $windows = @()
    $current = $null
    foreach ($line in $win) {
        $t = $line.ToString()

        if ($t -match '^\s*Window\{[^}]*\s+(u0\s+)?([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)') {
            if ($current) { $windows += $current }
            $current = [ordered]@{ Name = $matches[2]; Type = ''; Visible = $false; Focused = $false; Frame = ''; Owner = '' }
            continue
        }
        if (-not $current) { continue }

        if ($t -match 'mType=(\S+)') { $current.Type = $matches[1] }
        if ($t -match 'isVisible=(\w+)') { $current.Visible = ($matches[1] -eq 'true') }
        if ($t -match 'mViewVisibility=0x0') { $current.Visible = $true }
        if ($t -match 'mHasSurface=(\w+)') { $current.HasSurface = ($matches[1] -eq 'true') }
        if ($t -match 'mAttrs=\{[^}]*\s(\d+)x(\d+)') { $current.Frame = "$($matches[1])x$($matches[2])" }
        if ($t -match 'mOwnerUid=\d+\s+(\S+)') { $current.Owner = $matches[1] }
        if ($t -match 'mCurrentFocus|mFocusedApp') { $current.Focused = $true }
    }
    if ($current) { $windows += $current }

    # Also record which activity is resumed, so ad windows can be correlated with time.
    $top = (& $Adb shell dumpsys activity activities 2>&1) |
        Select-String -Pattern 'topResumedActivity' | Select-Object -First 1
    $topText = if ($top) { ($top.ToString().Trim() -replace '\s+', ' ') } else { '' }

    $samples += [pscustomobject]@{
        Ms = $elapsed
        Top = $topText
        Windows = $windows
    }

    Start-Sleep -Milliseconds $IntervalMs
}

Write-Host "`n=== per-sample window inventory ==="
foreach ($s in $samples) {
    $visible = $s.Windows | Where-Object { $_.Visible -or $_.HasSurface }
    $names = ($visible | ForEach-Object { $_.Name }) -join ', '
    Write-Host ("  [{0,5} ms] {1}" -f $s.Ms, $s.Top)
    Write-Host ("             windows: {0}" -f $names)
}

Write-Host "`n=== every distinct window name seen, with counts ==="
$all = $samples | ForEach-Object { $_.Windows } | Where-Object { $_ }
$all | Group-Object Name | Sort-Object Count -Descending |
    ForEach-Object { "  {0,4}x  {1,-70} type={2}" -f $_.Count, $_.Name, ($_.Group[0].Type) }

Write-Host "`n=== windows belonging to or overlaying the target ==="
$all | Where-Object { $_.Name -like "*$TargetPackage*" -or $_.Owner -like "*$TargetPackage*" } |
    Group-Object Name | ForEach-Object { "  {0,4}x  {1}" -f $_.Count, $_.Name }
