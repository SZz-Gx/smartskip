# Cold-launch an app and sample what SmartSkip sees during its splash window.
#
# Phase 1 samples the foreground activity every 200 ms, so the splash window can be
# located in time — it reveals how long the ad actually occupies the screen.
# Phase 2 asks the accessibility service for a scored capture of whatever is on
# screen, every 700 ms, so the real UI tree and per-node scoring land in logcat.
#
# Usage:
#   probe-splash.ps1 -TargetPackage com.zhihu.android
#   probe-splash.ps1 -TargetPackage com.sina.weibo -TotalSeconds 14

param(
    [Parameter(Mandatory = $true)][string]$TargetPackage,
    [string]$Adb = 'F:\ASSDK\platform-tools\adb.exe',
    [int]$TotalSeconds = 12
)

$ErrorActionPreference = 'Continue'
$self = 'io.github.szzgx.smartskip.debug'
$receiver = "$self/io.github.szzgx.smartskip.accessibility.InspectorCommandReceiver"
$action = 'io.github.szzgx.smartskip.action.REQUEST_SCORED_DUMP'

Write-Host "=== cold-launching $TargetPackage for ${TotalSeconds}s ==="
& $Adb logcat -c | Out-Null
& $Adb shell am force-stop $TargetPackage | Out-Null
Start-Sleep -Milliseconds 700
& $Adb shell monkey -p $TargetPackage -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null

$start = Get-Date
$activityTrace = @()

while (((Get-Date) - $start).TotalSeconds -lt $TotalSeconds) {
    $elapsed = [int]((Get-Date) - $start).TotalMilliseconds

    $top = (& $Adb shell dumpsys activity activities 2>&1) |
        Select-String -Pattern 'topResumedActivity|ResumedActivity' |
        Select-Object -First 1
    if ($top) {
        $line = ($top.ToString().Trim() -replace '\s+', ' ')
        if ($activityTrace.Count -eq 0 -or $activityTrace[-1].Line -ne $line) {
            $activityTrace += [pscustomobject]@{ Ms = $elapsed; Line = $line }
            Write-Host ("  [{0,5} ms] {1}" -f $elapsed, $line)
        }
    }

    & $Adb shell am broadcast -a $action -n $receiver 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
}

Write-Host "`n=== splash window (activity changes) ==="
$activityTrace | ForEach-Object { "  {0,5} ms  {1}" -f $_.Ms, $_.Line }

$log = & $Adb logcat -d -b main 2>&1

Write-Host "`n=== capture summaries for the target ==="
$log | Select-String -Pattern 'Inspector: dump' | Select-String -Pattern $TargetPackage |
    ForEach-Object { $_.ToString() }

Write-Host "`n=== candidates found in the target (if any) ==="
$targetLines = $log | Select-String -Pattern 'Inspector:'
$inTarget = $false
$reported = 0
foreach ($l in $targetLines) {
    $t = $l.ToString()
    if ($t -match 'Inspector: dump') {
        $inTarget = $t -match [regex]::Escape($TargetPackage)
        continue
    }
    if ($inTarget -and $t -match 'CANDIDATE') {
        Write-Host "  $t"
        $reported++
    }
}
if ($reported -eq 0) { Write-Host "  (none — no skip-shaped control in the captured frames)" }

Write-Host "`n=== distinct signals seen in the target ==="
$inTarget = $false
$signals = @{}
foreach ($l in $targetLines) {
    $t = $l.ToString()
    if ($t -match 'Inspector: dump') {
        $inTarget = $t -match [regex]::Escape($TargetPackage)
        continue
    }
    if ($inTarget -and $t -match ':: \[([^\]]+)\]') {
        foreach ($s in ($matches[1] -split ',')) {
            $k = $s.Trim()
            if ($k) { $signals[$k] = 1 + ($signals[$k] | ForEach-Object { $_ } | Select-Object -First 1) }
        }
    }
}
if ($signals.Count -eq 0) { Write-Host "  (none)" }
else { $signals.Keys | Sort-Object | ForEach-Object { "  $_" } }

Write-Host "`n=== SmartSkip decisions for the target ==="
$log | Select-String -Pattern 'SmartSkip/Svc' | Select-String -NotMatch 'no skip-shaped candidate' |
    ForEach-Object { $_.ToString() }
