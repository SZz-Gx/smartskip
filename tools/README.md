# tools/

Scripts used to test SmartSkip on a real device. They are tracked because they are part of how
this project obtains evidence, and because the traps they encode cost real time to find — see
[../docs/TESTING.md](../docs/TESTING.md).

They are not part of the Android build. Nothing here runs on a phone by itself; each script
drives a device over `adb`.

## Prerequisites

* `adb` on the path used by `-Adb` (the default points at a Windows SDK install)
* the debug APK installed *and* SmartSkip's accessibility service enabled and bound
* for `run-harness.ps1`: the adversarial harness, which is debug-only

The runner checks the service binding and refuses to run when it is missing, rather than
reporting zeros that look like detection failures.

## Scripts

### `run-harness.ps1` — the main test loop

Drives the fourteen adversarial scenarios and compares the engine's decisions against what each
scenario declares correct.

```powershell
pwsh tools/run-harness.ps1                      # observe: scoring and vetoes
pwsh tools/run-harness.ps1 -Act                 # act: the click path, rules confirmed
pwsh tools/run-harness.ps1 -Only bait,payment   # a subset
```

Ground truth is the harness's own `HARNESS-CLICKED` log line, never the engine's verdict: a line
containing `FAILURE` means the engine tapped something it must never tap.

Read the script's header before changing it. It encodes four specific traps, and each one produced
confidently wrong results before it was understood:

* `am force-stop` removes the accessibility service on HyperOS, so scenarios after the first
  evaluate nothing — switching uses `CLEAR_TASK|NEW_TASK` instead;
* a plain `am start` on a running top-most instance only delivers an intent and the screen does not
  change;
* the nudge tap that drives the engine is indistinguishable from an engine click in the
  `fullscreen` scenario;
* verdicts read from logcat race the service, so they are read from the database by row id.

### `harness_report.py` — the runner's reporting helper

Prints new database events, filtered by row id, with the verdict, the veto reasons and the top
scoring contributions. Called by the runner; usable standalone:

```bash
python tools/harness_report.py --since 42 --limit 8
```

### `probe-splash.ps1` — splash-window probe

Cold-launches one app and, while its splash window is up, samples the foreground activity and asks
the service for scored captures. Use it to find out what a real ad actually exposes.

```powershell
pwsh tools/probe-splash.ps1 -TargetPackage com.zhihu.android -TotalSeconds 14
```

### `probe-windows.ps1` — window inventory probe

Enumerates every window during an app's launch and reports which belong to it. This is how the
question "is the ad in a window we are not reading?" was answered: on the device tested, it is not
— a splash ad lives in the app's own `TYPE_APPLICATION` window, and ad SDKs hide the skip control
*inside* that tree rather than moving it elsewhere.

### `inspect_device_db.py` — database dump

Pulls and summarises the whole SmartSkip database: event counts per package, action/result
distribution, the text-feature vocabulary actually observed, veto reasons, the best-scoring rows
and the learned rules. Useful for answering "what has it been doing for the last few days?" after
a passive collection run.

## Scratch directory

`.tooling/` (git-ignored) holds a downloaded Gradle distribution, pulled database copies and commit
message scratch files. Nothing there is needed to use these scripts.
