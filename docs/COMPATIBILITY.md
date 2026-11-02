# Android compatibility and limitations

## SDK choices

| Setting | Value | Why |
|---|---|---|
| `minSdk` | 30 (Android 11) | The Cell Broadcast stack and its history provider live in the Mainline module, which itself requires API 30. Lower versions use a different stack. |
| `targetSdk` | 35 (Android 15) | The app is not distributed through Google Play, so nothing forces a higher target. Raising it should follow a test on that version. |
| `compileSdk` | 35 | Matches the target. |

Toolchain: AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21, JDK 17. **Android Studio Ladybug (2024.2) or newer is needed to open the project.** Command-line builds do not need Android Studio.

## Tested versions

| Android | API | Status | What was actually run |
|---|---|---|---|
| 11, 12, 12L | 30 to 32 | **Not run** | Nothing. Expected to work by design. |
| 13 | 33 | **Partly tested** | Stock `user/release-keys` emulator image, on an earlier version of the app: install, `pm grant`, provider Active, simulations. Real alert flow cannot be injected here. |
| 14 | 34 | **Tested on an earlier version** | `userdebug` emulator image: every scenario then in the suite, with real PDUs through the real stack. |
| 15 | 35 | **Tested** | `userdebug` emulator image, with the URL trigger. Version 1.0.0: with the app locked, and all lock checks on the signed release. Earlier versions: before the first unlock and during a normal lock. See the [results](HOW_IT_WORKS.md#results). |
| 16 | 36 | **Tested on an earlier version** | `userdebug` emulator image: every scenario then in the suite. |
| 17 | 37 | **Not run** | AOSP source reading only. |

**One real phone has been tested:** a retail Samsung phone on Android 16. The app reads the real alerts stored on it, and all three detection methods show Active. On retail Android 15, the grant is still unproven.

**A live alert reaching the app on a real phone and firing a real system has not been proven.** See [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md).

### What the AOSP source says

These come from reading AOSP `main` on 1 November 2026. Emulator runs have confirmed the route on Android 15 and 16, but they are not a test of Android 17, and OEM builds can differ.

| Fact | Source | Effect |
|---|---|---|
| `READ_CELL_BROADCASTS` is still `dangerous` | `core/res/AndroidManifest.xml` | The grant route still exists. |
| The provider still gates `/history` on that permission alone | `CellBroadcastService/AndroidManifest.xml`, `CellBroadcastProvider.java` | Reading should work unchanged. |
| The permission is in the Enhanced Confirmation protected list | `EnhancedConfirmationService.java` | Sideloaded apps are blocked in the **Settings UI** on Android 15+. |
| The permission grant code has no reference to Enhanced Confirmation | `PermissionService.kt` | `adb shell pm grant` should not be affected. |
| `pm install` allowlists restricted permissions by default and uses `PACKAGE_SOURCE_OTHER` | `PackageManagerShellCommand.java` | An adb install avoids the "always dangerous" install sources. |
| Shell holds `GRANT_RUNTIME_PERMISSIONS` | `packages/Shell/AndroidManifest.xml` | `pm grant` is a standard capability, not a loophole. |

## Platform behaviour that affects this app

| Version | Change | How it is handled |
|---|---|---|
| 11 | Package visibility | `QUERY_ALL_PACKAGES`. A `<queries>` filter was measured not to expose the system alert app. |
| 11 | Unused apps lose permissions | The provisioning script exempts the app. |
| 13 | Restricted settings for sideloaded apps | Provision over adb. Measured: mode `allow` after an adb install. |
| 13 | `POST_NOTIFICATIONS` runtime permission | Granted by the script. |
| 14 | Foreground services must declare a type | `specialUse`, with a stated subtype. Measured running on 14. |
| 15 | Some service types cannot start at boot | `specialUse` is not on the blocked list. Boot start measured on 14, 15 and 16. |
| 15 | Enhanced Confirmation Mode | Provision over adb. Measured: install, grant, notification and accessibility setup all work over adb on 15 and 16. |
| 15 | Notifications that look like one-time codes are hidden from ordinary listeners | Measured on 15, not on 16. See [Hidden notification text](#hidden-notification-text). |
| 15 | Edge-to-edge enforced for target 35 | Handled by `Scaffold` insets. |
| 17 | Advanced Protection limits accessibility to accessibility tools | Keep it off on this phone. The provider route is unaffected as far as is known. |

## Detection methods compared

Only the Cell Broadcast provider can fire a URL. The other two methods only record what they see.

| | Cell Broadcast provider | Accessibility | Notification |
|---|---|---|---|
| Setup | One adb grant | Enabled over adb | Enabled over adb |
| Can fire a URL | **Yes** | **No** | **No** |
| Sees an alert with the dialog showing | Yes | Yes | **No** |
| Works with the screen off and locked | Yes, measured | Yes, measured, because the dialog wakes the screen | Only after the dialog is left |
| Message ID, serial, PLMN, CMAS fields | Yes | No | No |
| Survives the app being down | Yes, rows persist | No | Partly, while the notification stays posted |
| `source` in the event log | `android_cell_broadcast` | `android_system_alert_ui`, unverified | `android_system_notification`, unverified |
| Survives force-stop | Yes | **No: Android disables it** | Yes, and it restarts the app |
| Reads every alert's text | Yes | Yes | **No:** Android can hide the text, and two unread alerts show only a count |

## OEM behaviour

**Only one OEM phone has been tested.** Everything else in this table is reasoning, not evidence.

| Device family | Expectation | Reason |
|---|---|---|
| The one Samsung phone tested, Android 16 | **Setup and history read measured** | It uses Google's `com.google.android.cellbroadcast*` packages, like the tested images. The app stayed alive and unfrozen for over 2 minutes in the background. Other Samsung models or regions may differ. Live capture, and hours with the screen off, are not measured. |
| Google Pixel | High confidence | Uses the same packages as the tested images. |
| Near-AOSP brands (Motorola, Nokia, Nothing) | Good | Likely to keep the module. |
| Xiaomi, Oppo, Vivo, Honor | Unknown | Heavy customization and aggressive background killing. |
| Huawei without Google services | Unknown, higher risk | Different system stack. |

The app does not depend on a list of OEM package names. At runtime it finds the system receiver of the emergency broadcast, and **Diagnostics** reports whether the provider exists. Part A of [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md) answers this for a given phone in minutes, without a broadcast.

**Recommendation:** get one unit of the model you plan to use, and run the phone check before getting more.

## Known limitations

### Dependence on a hidden permission

`READ_CELL_BROADCASTS` is marked `@hide` and `@SystemApi` in AOSP. It works, and AOSP documents it for alert history, but **it is not a public API with a compatibility promise**.

Cell Broadcast is also a Mainline module, so Google can change it through a Google Play system update, without an Android version update. If that closes the route, the **Dashboard** reports the provider as **Needs setup** or **Not available**, and the URL trigger cannot fire. Accessibility would still record alerts. If you run an event log server, its heartbeats report this too.

### The grant needs adb, once

Android offers no on-screen way to grant this permission. A person with physical access has to approve USB debugging once. That is an intended Android capability, not an exploit. The alternative AOSP documents is the default SMS role. This app does not implement it, because the phone would then have to act as an SMS app, and the route has not been tested.

### Cell Broadcast has no authentication

Android cannot prove who sent a Cell Broadcast. `verified: true` means only that the operating system's Cell Broadcast stack delivered it. A fake base station could produce the same result, and the URL trigger would fire. See [SECURITY.md](../SECURITY.md).

### First unlock after reboot

The app keeps its data in device-protected storage and starts at `LOCKED_BOOT_COMPLETED`, so it runs before the first unlock, as Android's own Cell Broadcast stack does. It fired before unlock on an Android 15 emulator. On one retail phone it started, read its alerts and URLs, and reached the network over Wi-Fi before unlock, though no alert arrived in that test. The accessibility and notification backups stay off until the first unlock. See [SETUP.md](SETUP.md#screen-lock).

### Hidden notification text

Android 15 added protection for one-time codes. If a notification's text looks like a code, ordinary notification listeners receive a placeholder instead. An alert can trigger this, because the check only looks at the shape of the text. Measured with real PDUs through the real stack:

| Text in the alert | Android 15 image | Android 16 image |
|---|---|---|
| No digits, `998`, `911` | Visible | Visible |
| `2026` (common year), `14:30`, `02/11/2026` | Visible | Visible |
| 9 or 10 digit numbers | Visible | Visible |
| Arabic-Indic digits such as `١١٢٢` | Visible | Visible |
| **4 digits such as `1122`** | **Hidden** | Visible |
| **Hijri year such as `1448`** | **Hidden** | Visible |
| **7 digits such as `8001240`** | **Hidden** | Visible |
| **`2026-11-02`** | **Hidden** | Visible |
| **6 digit reference** | **Hidden** | Visible |

Current AOSP limits this check to the default SMS app, which fits the Android 16 result, but the check runs in updatable system components, so **do not assume either behaviour on a given phone**.

This affects the notification method only. The provider and accessibility methods read the text normally.

When the text is hidden, the app never passes the placeholder off as the alert. If a detector that reads text is working and has reported an alert, the hidden notification is only audited. Otherwise the event carries `textHidden: true`, a note in `message` and no language, and someone must read the alert on the phone.

The app does not ask for the exemption that would reveal this text. That exemption would also expose one-time codes from every other app.

### Unread alert summary

While two or more alerts are unread, the alert app replaces the notification with a count such as "2 unread alerts". It contains no alert text, so the notification method alone misses that alert, and the app ignores the summary. The provider and accessibility methods are not affected.

### Force-stop

Force-stopping the app permanently disables its accessibility service until it is re-enabled. The Android source and emulator runs agree on this. OEM cleaner apps may do the same thing.

### Channels and drills

Channel plans differ by country. As one example, the AOSP configuration for MCC 420 (`values-mcc420`) maps channels 4370 to 4394, and real alerts on one phone used 4377 and 4370, with two languages in one message. Which channel official tests use **is not confirmed**.

Classification follows the 3GPP standard. A channel outside that standard stays unknown, and it never fires. See [Which channels can fire](URL_TRIGGER.md#which-channels-can-fire). If a drill arrives on a real alert channel, `testOrExercise` reads `false`, and with matching words the URL fires. Part C of the hardware procedure checks this.

### Emergency alert app settings

If someone turns off alerts in the phone's settings, the phone's modem is told not to listen, and nothing reaches any method. In the AOSP configuration for MCC 420 some channels are `always_on`, but monthly test channels are not.

### Geo-fenced alerts

A geo-fenced alert enters `/history` only after Android confirms the phone is inside the area. That can take as long as the alert's wait time. If the phone is outside it, the row never appears, which is correct behaviour.

### Isolated Wi-Fi

The app binds its sockets to the Wi-Fi network so that it works when Android prefers mobile data. On a Wi-Fi network with no internet, that has been exercised on an emulator only. Real access point behaviour, such as client isolation, is untested.

### Limits of emulator testing

The emulator proves everything from the PDU handler onward. It does not prove modem behaviour, or any country's real configuration: the emulator's SIM is MCC 310, so the `values-mcc420` overlay was never active there. The retail phone result covers reception and storage on that one phone.
