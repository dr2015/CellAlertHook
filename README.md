# CellAlertHook

CellAlertHook is an Android app that reads official emergency Cell Broadcast alerts (ETWS and CMAS) from Android's own store and calls a URL when an alert contains a phrase you set. When an alert says the threat has passed it calls a second URL, so with no server in between it can drive anything with a URL, such as a siren or PA controller, a smart building, a webhook or a home automation system.

> [!WARNING]
> **Test this on your own phone before you rely on it.**
>
> - **It is not a certified warning system.** Keep your official alerts and safety procedures.
> - **Cell Broadcast is not signed.** Android cannot prove who sent an alert. A fake base station could send text that matches your phrases, and the app would call your URL.
> - **There is no person in the loop.** Once **Armed**, the app calls the URL by itself. Nobody confirms the alert first.
> - **There is no warranty.** The software is provided as is.
> - **The full chain is not proven.** A live alert reaching the app on a real phone and firing a real system has never been shown. See [What has been proven](#what-has-been-proven).

## How it works

1. Android receives an alert and stores it in `content://cellbroadcasts/history`.
2. A one-time `adb shell pm grant app.cellalerthook android.permission.READ_CELL_BROADCASTS` lets the app read that store.
3. The app compares each new alert with your threat and all-clear phrases.
4. A threat calls the on URL. An all-clear calls the off URL.
5. Only the newest command is sent. A trigger older than the age limit is not sent. Failed calls retry with backoff.

Only alerts from Android's Cell Broadcast stack can fire. Text from the screen or notifications never fires, and neither do test channels, exercise channels or simulations.

The app works with the phone locked, and before the first unlock after a restart. More detail is in [How it works](docs/HOW_IT_WORKS.md).

## Requirements

| Item | Requirement |
|---|---|
| Phone | Android 11 (API 30) or newer. See [Compatibility](docs/COMPATIBILITY.md) for what has been tested. |
| SIM | An active SIM with radio coverage. Cell Broadcast does not need a data plan. |
| Network | A network the phone can use to reach both URLs |
| Computer | Used once, with USB. [Android platform tools](https://developer.android.com/tools/releases/platform-tools) (`adb`) and Node.js 20 or newer. |
| The system you control | One URL that turns it on and one that turns it off |

No root is needed. **USB is needed once.** Android does not give `READ_CELL_BROADCASTS` to an APK opened from a file. Without adb the app cannot work.

## Quick start

1. Clone this repository, which holds the setup tool.

   ```bash
   git clone https://github.com/dr2015/CellAlertHook.git
   cd CellAlertHook
   ```

2. Download the APK from the [Releases page](https://github.com/dr2015/CellAlertHook/releases).
3. On the phone, turn on USB debugging, connect it to the computer, and accept the prompt.
4. Install and grant:

   ```bash
   node tools/provision-device.js --apk <path to the APK>
   ```

5. Open the app and create a PIN of 6 to 12 digits.
6. On the **Dashboard**, turn on **Protect from closing** under **Setup needed**.
7. In **Settings**, paste both URLs, set the phrases for your country, and press **Save**.
8. Press **Check connection**. It sends no request, so it cannot fire anything.
9. When you are ready, turn on **Armed** and press **Save**. The **Dashboard** should read **Ready**.

**The default phrases come from real alerts in one country.** If you are anywhere else, set your own, or nothing will fire.

The full steps are in [Setup](docs/SETUP.md). To check what a phone exposes first, see the [quick phone check](docs/HARDWARE_VERIFICATION.md#quick-first-check-alerts-already-on-the-phone).

## Build from source

You need JDK 17 and the Android SDK with platform 35.

```bash
./gradlew :app:assembleDebug
node tools/provision-device.js --apk app/build/outputs/apk/debug/app-debug.apk
```

**Use a release build on any phone that holds real links.** The debug build has test commands that can call them. To sign a release, see [Setup](docs/SETUP.md#build-a-signed-release).

## What has been proven

| Question | Answer |
|---|---|
| Has an ordinary app read real over-the-air alerts? | **Yes, from the phone's store.** On one retail Android 16 phone. |
| Has real alert text called a URL on a real phone? | **Yes, but not from a live alert.** Stored alert text went through the real rules and called test URLs in about 0.2 s, screen off and locked. |
| Does it fire on the real Android stack? | **Yes, on an Android 15 emulator.** Version 1.0.0 fired with real PDUs through the telephony stack with the app locked, 4 of 4 scenarios. Earlier versions also fired before the first unlock and during a normal lock (12 of 12), about 0.3 s from entering Android to the URL call. |
| Other Android versions? | Emulator runs on Android 14, 15 and 16 on earlier versions of the app. The permission grant is proven on a stock Android 13 image and a retail Android 16 phone. |
| Has a live alert reached the app on a real phone and fired a real system? | **No.** |
| Has it been tested on other phone brands? | **No.** One phone only. |

The route depends on a permission Android does not promise to keep. Details are in [How it works](docs/HOW_IT_WORKS.md).

## Documents

| Document | Contents |
|---|---|
| [Setup](docs/SETUP.md) | Installing, granting and configuring |
| [URL trigger](docs/URL_TRIGGER.md) | Which alerts fire, phrases, active hours, app lock, preset builds, remaining risks |
| [How it works](docs/HOW_IT_WORKS.md) | Android's Cell Broadcast design, what an app can reach, results |
| [Compatibility](docs/COMPATIBILITY.md) | Android versions, phone brands, known limits |
| [Diagnostics](docs/DIAGNOSTICS.md) | Reading the app's status and solving problems |
| [Testing](docs/TESTING.md) | Unit, instrumented, simulated and emulator tests |
| [Hardware verification](docs/HARDWARE_VERIFICATION.md) | Procedure for a real phone and a real broadcast |
| [Test receiver](mock-server/README.md) | A stand-in for the system you control, and the optional event log protocol |
| [Security](SECURITY.md) | Reporting a problem, threat model, known limits |
| [Changelog](CHANGELOG.md) | Release notes |

## Project layout

| Path | Contents |
|---|---|
| `app/` | The Android app: Kotlin, Jetpack Compose, Room, WorkManager, OkHttp |
| `tools/` | Provisioning, the phone check, preset builds and the emulator tests |
| `mock-server/` | Test receiver in Node.js, with no dependencies. Not needed in normal use. |
| `poc/` | A minimal app that shows what an ordinary APK can reach |
| `docs/` | Guides and technical notes |

## License

MIT. See [LICENSE](LICENSE).
