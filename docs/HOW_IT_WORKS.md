# How it works

An ordinary third-party app, with no root, no privileged install and no OEM signing, can read real Cell Broadcast alerts from Android's own history provider. The one condition is a one-time `adb` command, because Android has no on-screen control for the permission.

## What is proven

| Claim | Status | Basis |
|---|---|---|
| An app can read structured alerts after `pm grant` | **Shown** | Android 14, 15 and 16 emulators, real stack, injected PDUs |
| Real over-the-air alerts can be read on a retail phone | **Shown** | 38 alerts, one retail Android 16 phone |
| The grant works on a retail build | **Shown** | That phone, and a stock Android 13 image |
| Alert text, channel and serial survive unchanged | **Shown** | Two-page Arabic and English PDUs |
| Fires locked, in Doze, before the first unlock and with the app locked | **Shown on an emulator** | Android 15, real PDUs through the telephony stack |
| Stored real alert text calls a test URL on a retail phone | **Shown, not a live alert** | About 0.2 s, screen off and locked |
| Works on Android 17 | **Not shown** | AOSP source review only |
| Works on other phone brands | **Not shown** | One phone tested |
| A live alert reaches the app on a real phone and fires a real system | **Not shown** | Needs a real broadcast. See [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md). |

The route depends on a permission Android does not promise to keep, and `verified: true` does not prove government origin.

## How Android handles a Cell Broadcast

```text
Modem  ->  RIL  ->  GsmInboundSmsHandler  ->  CellBroadcastService module
                                                   |
                       1. Check for a duplicate (compares the body too)
                       2. Insert a row in CellBroadcastProvider
                       3. Mark message_broadcasted = 1
                       4. Send SMS_EMERGENCY_CB_RECEIVED, by explicit package
                                                   |
                                     CellBroadcastReceiver (alert app)
                                                   |
                            Full-screen dialog, sound, vibration
                            Mark message_displayed = 1
```

Both components are in the Cell Broadcast Mainline module. On the tested builds they are `com.google.android.cellbroadcastservice` and `com.google.android.cellbroadcastreceiver`. The key point is step 2: the system writes the alert to a database, and a permission gates reading from it.

## What each interface allows

| Interface | Protection | Open to a third-party app? | Basis |
|---|---|---|---|
| `SMS_EMERGENCY_CB_RECEIVED` broadcast | Protected, sent by explicit package to the app in the Cell Broadcast APEX | **No** | Source, and measured: never delivered |
| `RECEIVE_EMERGENCY_BROADCAST` | `signature\|privileged` | **No** | Measured: `not a changeable permission type` |
| `MODIFY_CELL_BROADCASTS` | `signature\|privileged` | **No** | Source |
| `FULL_ACCESS_CELL_BROADCAST_HISTORY` | `signature` | **No** | Source |
| `content://cellbroadcasts` (full table) | Needs the signature permission above | **No** | Measured: `SecurityException` |
| `READ_CELL_BROADCASTS` | **`dangerous`**, hard restricted, `@hide` | **Yes, through `pm grant`** | Measured |
| `content://cellbroadcasts/history` | `READ_CELL_BROADCASTS` | **Yes** | Measured |
| `NotificationListenerService`, `AccessibilityService` | User-granted | Yes | Measured |

The framework's own comment on `READ_CELL_BROADCASTS` says it lets an app read received broadcasts and register a content observer, and AOSP documents granting it to the default SMS app. Granting it to any other app by `pm grant` is not documented. It works because the permission is `dangerous`, and because `adb install` allowlists restricted permissions. Extra `additional_cell_broadcast_receiver_packages` entries exist, but they need an OEM overlay, so an ordinary app cannot use them.

## How the app reads alerts

```text
          Android Cell Broadcast stack
                     |
      +--------------+---------------+
      |              |               |
  History        Alert dialog    Notification
  provider       (text only)     (after the dialog is left)
      |              |               |
      +--------------+---------------+
                     |
              AlertPipeline: classify, deduplicate, audit, store
                     |
              UrlTriggerService: rules, newest command only, retry, age limit
                     |
              On URL / Off URL
```

**History provider, the only method that can fire.** The read returns the full reassembled text along with `service_category`, `serial_number`, `plmn`, `lac`, `cid`, `language`, `priority`, `cmas_message_class` and `received_time`. The app registers a `ContentObserver`, then re-scans on a short schedule, because a row is inserted before it is marked broadcast, and on Android 14 that update sends no change notification. The observer is a hint and a periodic scan is the safety net. Rows persist, so an alert received while the app is down is found on the next scan. A first-run baseline stops old history being treated as new alerts.

**Accessibility, a backup that only records.** The real alert window exposes its text, including over the lock screen. The service is limited to the allowlisted alert packages, stops reading if a window has a password or editable field, and never performs an action, so it cannot dismiss an alert. It sees nothing after a force-stop, for an alert that shows no window, or on an OEM alert window that exposes no text. Going by published descriptions, the same applies with Advanced Protection on. A carrier setting, `disable_capture_alert_dialog`, can add `FLAG_SECURE` to the dialog, and that case was not tested. Text from this method has no channel, serial or severity.

**Notification, a backup that only records.** On stock configuration an emergency alert shows a full-screen dialog and **posts no notification**. Measured: 0 notifications with the dialog up. The notification appears only after someone leaves the dialog, which never happens on an unattended phone. The listener stays enabled because its binding makes Android restart the app within about a second of a force-stop.

**Other sources.** [`AlertDetector`](../app/src/main/java/app/cellalerthook/detection/AlertDetector.kt) and `DetectorRegistry` let a new source join the pipeline, such as a USB modem using `AT+CSCB` and `+CBM`, a privileged system app, or the default SMS role. None of these has been tested.

## Behaviour shaped by testing

| Behaviour | Why |
|---|---|
| A new message code is always a new alert | Real warnings repeat word for word, and one can follow an all-clear within minutes |
| Identifiers alone never prove a repeat | Networks reuse the 10-bit message code, so a new alert can carry an old serial. AOSP compares the body for the same reason. |
| An undismissed dialog is not a new incident | On an unattended phone the same words stay on screen for hours |
| Alerts read together are handled as one batch | After downtime, a warning and its all-clear can be found at once. Sending as each is read would blip the on URL for a threat already over. |
| `/history` is rechecked every 100 ms for the first 3 s after a change | The row can lag the change notification |
| HTTP connections are not pooled | An alert can follow hours of silence, and a socket the network has quietly dropped would stall the send |
| Hidden notification text is never treated as the alert | See [Hidden notification text](COMPATIBILITY.md#hidden-notification-text) |

## What real alerts look like

These are from 38 stored alerts on one phone. Other countries will differ.

| Finding | Effect |
|---|---|
| Two languages arrive **in one message**, with no language field | The app's `language` reads `mixed` |
| Channels used: 4377 (27 alerts), 4370 (9), 4396 (2) | All classified as real alert channels |
| 38 alerts carry only 5 different texts | Real warnings repeat word for word |
| Every alert has its own message code, and update number 0 | A message code tells two identical warnings apart |
| Warnings and all-clears share channels | Only the words can tell them apart |
| The rules sorted them into 18 threats, 18 all-clears and 2 ignored | Replayed in order on an emulator, they gave 18 on and 18 off calls, alternating exactly |

## Results

Times below come from injected PDUs on an emulator unless stated. A real phone will differ.

| Condition | Result |
|---|---|
| Android 15, normal lock, screen off: threats and all-clears | 12 of 12. 0.3 to 0.8 s, and about 1.4 s after 3 minutes left alone. |
| Android 15, before the first unlock, nobody having unlocked it | Threat to on URL 125 ms and 1.0 s in two boots. All-clear to off URL 128 ms and 950 ms. |
| Android 15, app locked | 4 of 4 fired |
| Android 15, warning to on URL, quiet device | 337 ms and 273 ms in two runs. About 3 s right after a fresh install on a busy host. |
| Retail phone, stored real alert text | Screen on: on URL 239 ms, off URL 157 ms. Screen off and locked: on URL 219 ms, off URL 165 ms. |

The retail times start from a test command, so they leave out the time to read a new alert from Android. **They are not a live alert.**

A retail phone was also restarted with its screen lock on, and nobody unlocked it. The app started by itself, first reporting 53 s after the restart. It read its alerts, both URLs and the armed state, and it reached a computer over Wi-Fi. Three reports arrived on time, then the next took about 2.5 minutes, likely because the processor slept. A real alert should wake the phone through its radio, but that is not proven, and neither is the app firing in this state on a real phone.

The scenario suite also ran on Android 14, 15 and 16 on earlier versions of the app. See [COMPATIBILITY.md](COMPATIBILITY.md#tested-versions).

## Is the grant a bypass of Android security?

No exploit, root or signature trick is involved. `pm grant` is a standard shell capability, and the shell's manifest holds `GRANT_RUNTIME_PERMISSIONS`. It needs someone with the unlocked phone to approve USB debugging. The permission is `dangerous`, the class that users may grant.

However, Android 15 added this permission to a list it protects from **sideloaded apps in the Settings UI**, to stop users being tricked by malicious apps. The adb route was not its target, but it does sit outside the flow Android intends for most users. On a phone you own and manage, that is a reasonable choice.

## Risks

| Risk | Likelihood | Effect | What to do |
|---|---|---|---|
| A phone has no readable provider | Unknown | The URL trigger cannot fire | Run Part A of the hardware procedure first |
| A Google Play system update or a new Android version closes the route | Low to medium, over time | Direct read stops, and the URL trigger cannot fire | The **Dashboard** reports it |
| A spoofed broadcast from a fake base station | Low | The URL fires | Nothing in the app can stop this. See [SECURITY.md](../SECURITY.md). |
| Someone force-stops the app, or an OEM cleaner kills it | Medium | Accessibility turns off | The provider continues. The **Dashboard** reports it. |
| A drill arrives on a real alert channel | Unknown | With matching words, the URL fires | Part C of the hardware procedure records this |

## Sources

AOSP, read at `main` on 1 November 2026:

- [CellBroadcastHandler.java](https://android.googlesource.com/platform/packages/modules/CellBroadcastService/+/refs/heads/main/src/com/android/cellbroadcastservice/CellBroadcastHandler.java), [CellBroadcastProvider.java](https://android.googlesource.com/platform/packages/modules/CellBroadcastService/+/refs/heads/main/src/com/android/cellbroadcastservice/CellBroadcastProvider.java)
- [CellBroadcastAlertService.java](https://android.googlesource.com/platform/packages/apps/CellBroadcastReceiver/+/refs/heads/main/src/com/android/cellbroadcastreceiver/CellBroadcastAlertService.java), [CellBroadcastAlertDialog.java](https://android.googlesource.com/platform/packages/apps/CellBroadcastReceiver/+/refs/heads/main/src/com/android/cellbroadcastreceiver/CellBroadcastAlertDialog.java)
- [values-mcc420/config.xml](https://android.googlesource.com/platform/packages/apps/CellBroadcastReceiver/+/refs/heads/main/res/values-mcc420/config.xml): an example channel configuration
- [GsmInboundSmsHandler.java](https://android.googlesource.com/platform/frameworks/opt/telephony/+/refs/heads/main/src/java/com/android/internal/telephony/gsm/GsmInboundSmsHandler.java): the test hook
- [core/res/AndroidManifest.xml](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml), [EnhancedConfirmationService.java](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/service/java/com/android/ecm/EnhancedConfirmationService.java), [PackageManagerShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerShellCommand.java), [Android documentation: Cell Broadcast module](https://source.android.com/docs/core/ota/modular-system/cellbroadcast)
