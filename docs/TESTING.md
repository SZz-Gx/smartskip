# Testing SmartSkip

This document records how SmartSkip is tested, and — more usefully — the traps that were hit while
working out how to test it. Most of the effort in this project went into *obtaining evidence*, not
into writing the engine, and the traps below each cost a run that produced confidently wrong
results.

## The two layers

| Layer | Question | Asset | Repeatable |
|---|---|---|---|
| **A. Mechanism** | Does the engine read the tree, score, veto, click and verify correctly on a real device? | The adversarial harness | Yes, fully |
| **B. Coverage** | What do real splash ads look like, and what are the real rates? | A corpus, accumulated passively | Statistics once a corpus exists |

Layer A needs no real ad. Layer B cannot be forced: ad delivery is not deterministic, and eight cold
launches of ad-bearing apps produced exactly one real splash ad.

## Layer A — the adversarial harness (debug only)

Lives in `app/src/debug/java/io/github/szzgx/smartskip/testharness/`, declared only in the debug
manifest, so it cannot appear in a build that ships. Verified:

```
aapt2 dump xmltree --file AndroidManifest.xml app-release-unsigned.apk | grep -i harness
# no output
```

### Why a harness rather than real ads

Real ads cannot test the click path, and they cannot test fake skip buttons at all:

* the one real splash ad captured on a device exposed **no skip control** in its accessibility tree —
  only an offer, an auto-dismiss countdown, and a "claim" button. Hiding the control is a capability
  boundary, not a defect, and it means there is nothing to click.
* a real ad has no reason to *fake* a control it can simply hide. The fake-skip threat is therefore
  unreachable by observing real ads, which is exactly why it has to be reproduced deliberately.

### Running it

```bash
# adb needs the service enabled and bound; the runner checks this and refuses to run otherwise
pwsh .tooling/run-harness.ps1            # observe: scoring and vetoes
pwsh .tooling/run-harness.ps1 -Act       # act: the click path, with rules confirmed
pwsh .tooling/run-harness.ps1 -Only bait,payment
```

Ground truth is the **harness's own** `HARNESS-CLICKED` log line, never the engine's verdict. A line
containing `FAILURE` means the engine tapped something it must never tap, and the run has failed
whatever the engine says about itself. An engine that reports a click the screen never received is
wrong about itself, and only this comparison can catch that.

### The scenarios

See `TestScenario`, which states in advance what correct looks like for each case. The two that
matter most:

* **`bait`** — a control whose label carries skip wording *and* an inducement. Must never be tapped,
  and must be refused by a **hard veto**, not by a low score: a low score is a number the learning
  system is designed to move.
* **`payment`** — a skip-shaped control on a payment screen.

## Layer B — accumulating a corpus

Not yet implemented. The plan is to record every splash-window tree that yields a candidate, plus
the ones that do not, so that real-world layers and misses can be counted rather than guessed. Until
that exists, the four metrics in the design document (skip rate, miss rate, mis-click rate,
dangerous mis-click rate) cannot be reported, and the README says so.

## Traps that produced wrong results

Each of these cost a run. They are recorded because every one of them looks like a detection failure
rather than a measurement failure.

### 1. `am force-stop` removes the accessibility service

HyperOS removes SmartSkip from `enabled_accessibility_services` when the app is force-stopped, and
does not restore it. A runner that force-stops between scenarios therefore evaluates **nothing** from
the second scenario onwards, and reports zeros that look exactly like "the engine detects nothing".

Fix: switch scenarios with `am start -f 0x10008000` (`CLEAR_TASK|NEW_TASK`), which replaces the
Activity without touching the process. The runner now also refuses to start unless the service is
bound, rather than reporting zeros.

### 2. A plain `am start` on a running top-most instance only delivers an intent

```
Warning: Activity not started, intent has been delivered to currently running top-most instance.
```

The screen does not change, so every iteration re-runs the first scenario. `onNewIntent` handling
exists as a courtesy, but the flags above are what actually make the switch reliable.

### 3. The nudge tap is indistinguishable from an engine click

The service only evaluates a screen when it receives an accessibility event, so the runner nudges the
window with `adb shell input tap`. In the `fullscreen` scenario the whole screen is one clickable node
by design, so the nudge lands on it and the harness logs a click the engine never made. All other
scenarios' controls now sit clear of the nudge points, and the script documents the remaining
artefact. Judge `fullscreen` by the engine's `action=` values, not by the harness click line.

### 4. Reading verdicts from logcat races the service

`logcat -c` followed by work then `logcat -d` loses lines produced during the clear, and a vendor ROM
with a 2 MiB default buffer rotates them away under its own chatter. The durable record is SmartSkip's
own database, so the runner reads that, filtered by row id so each scenario reports only its own
events.

### 5. A fixture whose accessibility tree is not what it looks like

The first harness applied `Modifier.clickable` to a `Box` with a `Text` child. The accessibility tree
then exposed an **unclickable** `TextView` holding the label, with the clickable parent as a separate
node — so the engine scored the text node, `performAction` on it would have done nothing, and the
scenario did not test what it claimed. The label is now attached to the node that owns the click
action, via `contentDescription`, which is also how an icon-only skip button presents itself.

### 6. Reinstalling while the app is running

`adb install -r` deletes the APK underneath a live process. The running process then fails to load
its own resources (`Failed to load asset path .../base.apk`) and the accessibility service crashes.
Stop the app before reinstalling.

### 7. Reinstalling too often trips HyperOS install protection

After enough install/uninstall cycles the device starts refusing with
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`, which requires confirmation on the device
and cannot be cleared from adb. The harness is worth installing once and driving repeatedly, rather
than reinstalling per change.

## What has actually been verified on a device

Recorded here so the distinction between "tested" and "reasoned about" stays visible.

| Item | Evidence |
|---|---|
| Service binds, minimal capability | `dumpsys accessibility`: `capabilities=1`, no gestures, no screenshot |
| Release APK has no network permission | `aapt2 dump permissions` |
| Release manifest keeps the inspector guard, debug lifts it | merged manifests |
| Release APK excludes the harness | `aapt2 dump xmltree` |
| Tree capture, scoring, explainability | per-frame contribution logs; inspector captures |
| **Fake skip button (label carries both signals)** | `action=VETO VETO=SKIP_WORDING_BAIT`, harness received no click |
| **Fake skip button (inducement on a sibling)** | same, after the fix |
| Skip wording that is not clickable | `action=VETO VETO=NOT_ACTIONABLE` |
| Skip text inside ad creative | scored 0, `risk_download_context` penalty |
| Editable and password fields | produce **no candidate at all** |
| First-sight gate | score 67–75 refused at threshold 101 |
| A successful automatic click | **NOT YET VERIFIED** — needs a device that accepts an install |
| Post-click verification of a real tap | **NOT YET VERIFIED** |
| Rate limiter and circuit breaker on a device | **NOT YET VERIFIED** (unit tested only) |
