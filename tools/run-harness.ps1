# Run the debug test harness across every adversarial scenario.
#
#   observe — auto-skip off: scoring and vetoes only
#   act     — auto-skip on with rules confirmed: the click path runs
#
# Ground truth is the harness's own HARNESS-CLICKED line, not the engine's verdict. A
# FAILURE line means the engine tapped something it must never tap, and the run has failed
# whatever the engine says about itself.
#
# Traps this script avoids:
#  1. am force-stop kills the accessibility service and HyperOS does not restore it, so a run
#     that force-stops between scenarios evaluates nothing from the second onwards. Scenario
#     switching uses CLEAR_TASK|NEW_TASK, which replaces the Activity without killing the process.
#  2. A plain am start on a running top-most instance only delivers an intent; the screen does
#     not change. The explicit flags are what make the scenario switch.
#  3. Reading verdicts from logcat races the service. The durable record is SmartSkip's own
#     database, filtered by row id so each scenario reports only its own events.

param(
    [string]$Adb = 'F:\ASSDK\platform-tools\adb.exe',
    [string]$Pkg = 'io.github.szzgx.smartskip.debug',
    [string]$Python = 'C:\Users\23267\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe',
    [string]$Report = 'D:\codeTest\smartskip\tools\harness_report.py',
    [string]$DbDir = 'D:\codeTest\smartskip\.tooling\device-db',
    [switch]$Act,
    [string[]]$Only,
    [int]$SettleMs = 1800
)

$ErrorActionPreference = 'Continue'

$activity = "$Pkg/io.github.szzgx.smartskip.testharness.TestHarnessActivity"
$startFlags = '0x10008000'

$scenarios = @(
    @{ Key='plain_skip';          Note='standard skip chip' }
    @{ Key='bait';                Note='FAKE skip: skip+inducement on ONE control' }
    @{ Key='creative';            Note='skip text inside ad creative' }
    @{ Key='video';               Note='skip wording on a video surface' }
    @{ Key='countdown';           Note='skip + countdown' }
    @{ Key='payment';             Note='skip control on a payment screen' }
    @{ Key='editable';            Note='editable field labelled skip' }
    @{ Key='password';            Note='password field' }
    @{ Key='multiple';            Note='multiple skip-shaped controls' }
    @{ Key='fullscreen';          Note='full-screen clickable claiming skip' }
    @{ Key='nonclick';            Note='skip wording, not clickable' }
    @{ Key='close';               Note='close glyph' }
    @{ Key='cta';                 Note='call to action, no skip wording' }
    @{ Key='countdown_neighbour'; Note='skip with adjacent countdown chip' }
)

function Test-ServiceBound {
    $d = & $Adb shell dumpsys accessibility 2>&1
    foreach ($line in $d) {
        $t = $line.ToString()
        if ($t -match 'Bound services:\{(.+)\}') { return $Matches[1].Trim().Length -gt 0 }
    }
    return $false
}

function Enable-Service {
    $comp = "$Pkg/io.github.szzgx.smartskip.accessibility.SmartSkipAccessibilityService"
    for ($i = 1; $i -le 6; $i++) {
        & $Adb shell "settings put secure accessibility_enabled 1" | Out-Null
        & $Adb shell "settings put secure enabled_accessibility_services '$comp'" | Out-Null
        Start-Sleep -Seconds 2
        $v = (& $Adb shell settings get secure enabled_accessibility_services).Trim()
        if ($v -eq $comp -and (Test-ServiceBound)) { return $true }
        & $Adb shell am start -n "$Pkg/io.github.szzgx.smartskip.ui.MainActivity" | Out-Null
        Start-Sleep -Seconds 2
    }
    return $false
}

function Set-AutoSkip([bool]$enabled) {
    $value = if ($enabled) { 'true' } else { 'false' }
    & $Adb shell am broadcast -a io.github.szzgx.smartskip.action.SET_AUTOSKIP --ez enabled $value 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
}

function Confirm-AllRules {
    & $Adb shell am broadcast -a io.github.szzgx.smartskip.action.CONFIRM_RULES --es package $Pkg 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
}

function Clear-Rules {
    & $Adb shell am broadcast -a io.github.szzgx.smartskip.action.RESET_RULES 2>&1 | Out-Null
    Start-Sleep -Milliseconds 700
}

function Pull-Database {
    New-Item -ItemType Directory -Force -Path $DbDir | Out-Null
    foreach ($f in @('smartskip.db','smartskip.db-wal','smartskip.db-shm')) {
        & $Adb exec-out run-as $Pkg cat "databases/$f" > "$DbDir\$f" 2>$null
    }
}

function Show-NewEvents([int]$since) {
    Pull-Database
    $out = & $Python $Report --since $since 2>&1
    $maxId = $since
    foreach ($line in $out) {
        $t = $line.ToString()
        if ($t -match '^__MAXID__=(\d+)') { $maxId = [int]$Matches[1]; continue }
        Write-Host "      $t"
    }
    return $maxId
}

function Invoke-Scenario([string]$key) {
    if (-not (Test-ServiceBound)) {
        Write-Host "      !! service not bound; re-enabling"
        if (-not (Enable-Service)) { throw "cannot get the accessibility service bound" }
    }

    & $Adb logcat -c | Out-Null
    & $Adb shell am start -n $activity --es scenario $key -f $startFlags 2>&1 | Out-Null
    Start-Sleep -Milliseconds 1500
    # Nudge points chosen to miss every scenario's controls on a 1200x2670 screen:
    # no control occupies y >= 2000, and x >= 80 avoids the top-left labels.
    & $Adb shell input tap 950 2150 | Out-Null
    Start-Sleep -Milliseconds $SettleMs
    & $Adb shell input tap 950 2350 | Out-Null
    Start-Sleep -Milliseconds $SettleMs

    $log = & $Adb logcat -d -b main 2>&1
    $launch = @($log | Select-String -Pattern 'SmartSkip/Harness: scenario=' |
        ForEach-Object { ($_.ToString() -replace '.*scenario=', '') })
    $clicks = @($log | Select-String -Pattern 'HARNESS-CLICKED' |
        ForEach-Object { ($_.ToString() -replace '.*HARNESS-CLICKED ', '') })

    [pscustomobject]@{
        Scenario = $key
        Launch   = if ($launch.Count -gt 0) { $launch[0] } else { '(none)' }
        Clicks   = $clicks
    }
}

Write-Host "################ PRE-FLIGHT ################"
if (-not (Test-ServiceBound)) {
    Write-Host "service not bound; enabling..."
    if (-not (Enable-Service)) {
        Write-Host "FATAL: could not bind SmartSkip's accessibility service. Aborting rather than"
        Write-Host "       reporting zeros that would look like detection failures."
        exit 1
    }
}
Write-Host "accessibility service is bound."

$list = if ($Only) { $scenarios | Where-Object { $Only -contains $_.Key } } else { $scenarios }

<#
  KNOWN ARTEFACT in the ullscreen scenario.
  That scenario is a full-screen clickable node by design, so the script's own nudge tap lands on
  it and the harness logs a click the engine never made. The engine's events for that scenario all
  say action=SCORE. Every other scenario's controls now sit clear of the nudge points.
  A trustworthy fix is to mark nudge-initiated clicks explicitly; until then, judge ullscreen
  by its engine events, not by the harness click line.
#>
Write-Host "
################ OBSERVE (auto-skip OFF) ################"
Set-AutoSkip $false
Clear-Rules
$watermark = 0

foreach ($s in $list) {
    Write-Host "`n--- $($s.Key)  [$($s.Note)]"
    $r = Invoke-Scenario -key $s.Key

    if ($r.Launch -notlike "*$($s.Key)*") {
        Write-Host "      <<< SCENARIO DID NOT SWITCH: $($r.Launch)"
    }

    $watermark = Show-NewEvents -since $watermark

    if ($r.Clicks.Count -gt 0) {
        Write-Host "      !! harness received click(s) with auto-skip OFF: $(($r.Clicks | Select-Object -Unique) -join '; ')"
    }
}

if ($Act) {
    Write-Host "`n`n################ ACT (auto-skip ON, rules confirmed) ################"
    Set-AutoSkip $true
    $failures = @()

    foreach ($s in $list) {
        Write-Host "`n--- $($s.Key)  [$($s.Note)]"

        Invoke-Scenario -key $s.Key | Out-Null
        Confirm-AllRules
        $r = Invoke-Scenario -key $s.Key

        $failure = @($r.Clicks | Where-Object { $_ -match 'FAILURE' } | Select-Object -Unique)
        $legit = @($r.Clicks | Where-Object { $_ -notmatch 'FAILURE' } | Select-Object -Unique)

        if ($failure.Count -gt 0) {
            Write-Host "      RESULT: *** FAIL *** tapped a control that must never be tapped:"
            $failure | ForEach-Object { Write-Host "          $_" }
            $failures += "$($s.Key) -> $($failure -join ', ')"
        } elseif ($legit.Count -gt 0) {
            Write-Host "      RESULT: clicked -> $($legit -join '; ')"
        } else {
            Write-Host "      RESULT: nothing was clicked"
        }

        Write-Host "      --- engine events ---"
        $watermark = Show-NewEvents -since $watermark
    }

    Write-Host "`n`n################ ACT SUMMARY ################"
    if ($failures.Count -eq 0) {
        Write-Host "No control that must never be tapped was tapped."
    } else {
        Write-Host "FAILURES ($($failures.Count)):"
        $failures | ForEach-Object { Write-Host "  $_" }
    }

    Set-AutoSkip $false
}

Write-Host "`nDone."
