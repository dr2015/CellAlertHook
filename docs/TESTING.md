# Testing

Tests fall into four levels, and each proves something different. **None of them involves a real over-the-air broadcast.** That needs [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md).

| Level | Proves | Needs |
|---|---|---|
| Unit tests | Processing, deduplication, delivery and trigger rules, payloads | JDK 17 |
| Instrumented tests | Real SQLite and the real Android Keystore | One device |
| Emulator end-to-end | The real Android stack from the PDU handler to the URL call | Userdebug emulator |
| Retail image checks | The permission path on a release-keys build | Stock emulator |

## Unit and instrumented tests

```bash
./gradlew :app:testDebugUnitTest
```

Set `ANDROID_SERIAL` when several devices are attached, because otherwise Gradle installs on all of them.

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest
```

Node tests:

```bash
cd mock-server && npm test
```

```bash
node --test tools/test/build-preset-apk.test.js tools/test/cb-inject.test.js tools/test/check-phone.test.js tools/test/provision-device.test.js
```

## App lock

```bash
node tools/lab-lock.js --device emulator-5556 --apk <release APK>
```

This drives the real screens: creating the PIN, wrong tries and the wait, hidden links, **Check connection** sending no request, Android's consent screen, and the greyed-out buttons. It needs the test receiver on port 8787. It wipes the app, so it refuses to run on anything but an emulator.

To test a preset build, build it with test links and add `--preset-pin <pin>`. Never point this at an APK holding real links, because it presses **Call on URL**.

## Simulated tests in the app

These use **Diagnostics > Test tools** and need no scripts. Everything is labelled SIMULATED and goes to the test endpoint of the event log server.

| Test | What runs | What it proves |
|---|---|---|
| A: notification | Posts a local notification, read by the real listener | Notification parsing and delivery |
| B: pop-up | Opens `SimulatedAlertActivity`, read by the real accessibility service | Window extraction logic. **Not** access to the real system dialog. |
| C: send to server | Sends a simulated event straight through the pipeline | Formatting, auth, acknowledgment, retry and offline storage |

To exercise retry and offline storage with Test C, use the test receiver's fault injection or turn off Wi-Fi first. The event stays in **Alerts** as Pending and is delivered when the fault clears.

A simulation can never reach the production route, and it can never fire a URL:

- It is sent to `testAlertsPath`.
- **Settings** refuses a test path equal to the alert path.
- The delivery service blocks the send if they ever match anyway.
- The server's routes each refuse the other's traffic.
- The URL trigger rejects any simulated event.

## Emulator end-to-end: real stack, injected PDU

The emulator supports controlled Cell Broadcast testing, on the right image.

Android's `GsmInboundSmsHandler` registers a test hook, `TEST_TRIGGER_CELL_BROADCAST`, only when `ro.debuggable=1`. It hands a raw PDU to the same handler the modem feeds, so everything after the radio is real: the decoder, duplicate detection, the provider, the alert app and its dialog.

| Image | `ro.debuggable` | Injection |
|---|---|---|
| `google_apis` x86_64 (API 34, 35 and 36, userdebug, dev-keys) | 1 | Works |
| `google_apis_playstore` x86_64 (API 33, user, release-keys) | 0 | The hook does not exist |

An SMS or an ordinary notification is not a substitute for this, and neither is used. `adb emu sms` is a different path that never reaches Cell Broadcast.

### Run it

Start the test receiver:

```bash
CELLALERTHOOK_TOKEN=lab-device-token-1 node mock-server/server.js --port 8787
```

Run all scenarios on a `google_apis` emulator. Setup is automatic, and scenarios **uninstall any existing copy of the app first**:

```bash
node tools/lab-e2e.js --device emulator-5556
```

Run a subset, or the checks for a retail image:

```bash
node tools/lab-e2e.js --device emulator-5556 --only S01,S03
```

```bash
node tools/lab-e2e.js --device emulator-5558 --mode stock
```

Inject a single alert by hand:

```bash
node tools/cb-inject.js --device emulator-5556 --id 4370 --lang ar --text "نص الرسالة"
```

Latency starts just before the last page is sent with `adb`, so it is an upper bound that includes adb and `am` start-up. The harness waits for a device to settle first, because a just-booted device mostly measures its own boot work.

### What the scenarios cover

| ID | Scenario |
|---|---|
| S01 | App visible: multi-page Arabic alert reaches the server verbatim |
| S02 | App in background: English alert |
| S03 | Screen off and PIN locked |
| S04 | Forced Doze deep idle |
| S05 | Process killed: alert arrives before the restart |
| S06 | Force-stop: the app revives, and the accessibility loss is reported |
| S06b | App fully down: alert recovered from the provider at next launch |
| S06c | Arabic and English on separate channels, back to back |
| S07 | Network lost: queued offline, delivered once on reconnection |
| S08 | Server errors: retried with backoff until acknowledged |
| S09 | Lost acknowledgment: the retry is idempotent |
| S10 | Repeated broadcast: same text and message code makes no new incident |
| S10b | Old serial on new text: never suppressed |
| S10c | Identical words on a new message code are a new alert |
| S11 | Exercise channel flagged as a test |
| S12 | Accessibility alone reads the real dialog; result stays unverified |
| S13 | Re-granting upgrades the same event in place |
| S14 | Notification alone: silent while the dialog shows, captured after leaving it |
| S14b | Notification alone, code-like alerts: hidden or not, each is reported and no placeholder is sent |
| S14c | All detectors on: leaving a code-like alert's dialog adds no second event |
| S15 | Simulations stay on the test route |
| S16 | Reboot: service returns without opening the UI |
| S16b | PIN-locked reboot: an alert is handled before anyone unlocks the phone |
| S16c | Unattended dialog: re-reading it past the duplicate window sends nothing new |
| S17 | Heartbeat reports detector health |
| T01 | Warning: the on URL is called at once |
| T02 | Repeated warning calls nothing more, and the all-clear calls the off URL |
| T03 | Warning, all-clear, identical warning: on, off, on |
| T04 | An alert no rule matches calls nothing |
| T05 | The exercise channel calls nothing, even with the real words |
| T06 | Screen text alone calls nothing until the provider confirms it |
| T07 | The URL fails three times: retried until it gets through, once |
| T08 | A trigger stuck behind failures can never reach the target after the all-clear |
| T09 | Warning and all-clear both arrive while the app is down: the on URL is never called |
| T10 | Not armed: a warning calls nothing |
| T11 | After a reboot the trigger still works without opening the app |

Retail image checks:

| ID | Scenario |
|---|---|
| K01 | `pm grant` works for a third-party app, and the provider shows Active |
| K02 | The injection hook does not exist |
| K03 | The signature permission cannot be granted |
| K04 | Simulations stay on the test route |

### Running on a newer Android version

Install a `google_apis` x86_64 image for the API level, create an AVD, then run the same commands. Check `adb shell getprop ro.debuggable` first. See [COMPATIBILITY.md](COMPATIBILITY.md) for what is still unverified.

### Emulator problems

These are host and emulator faults that came up in testing. They are not app faults.

**Reboots.** On Android 16 with `-gpu swiftshader_indirect`, the emulator crashed during guest reboots with a null pointer in `libgfxstream_backend.dll`. `-gpu guest` avoids that crash. The guest also froze during reboots on Android 15 and 16, and then `adb` stopped answering. An ordinary app cannot freeze `adb`, but the cause is not proven. If S16, S16b or T11 ends in an `adb` timeout, restart the emulator and run them again.

To test a cold boot, shut the emulator down with `adb shell sync`, then `adb shell svc power shutdown`. An `adb emu kill` straight after an install can leave a damaged APK, which Android reports as `Failed to open APK`.

**Memory.** When the host runs short of memory, the guest runs very slowly and timings become meaningless. Check free memory before trusting a slow result.

**A stray GLES library on Windows.** A headless emulator can crash with an access violation in `libGLESv2.dll` if another program left an old copy in the Windows system folder. That copy wins the DLL search over the emulator's own, so no `-gpu` flag changes anything. A workaround that leaves the system folder and the SDK untouched:

1. Copy the `emulator` folder elsewhere.
2. Copy `libGLESv2.dll` and `libEGL.dll` from its `lib64/gles_swiftshader` folder into its `qemu/windows-x86_64` folder.
3. Run the copied `emulator.exe`, with `ANDROID_SDK_ROOT` still pointing at the real SDK.
