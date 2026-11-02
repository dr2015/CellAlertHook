# Cell Broadcast access proof of concept

The smallest possible test of one question: what can an ordinary third-party APK observe from the Cell Broadcast stack? It has no dependencies and builds with plain SDK tools.

The build script runs in PowerShell. It needs a JDK, build-tools 34.0.0 and the `android-34` platform. If your SDK is not in the default folder, add `-Sdk <folder>`.

```powershell
poc/cb-provider-poc/build-poc.ps1
adb install -r poc/cb-provider-poc/build/cb-provider-poc.apk
adb shell am start -n app.cellalerthook.viewer/.PocActivity
adb logcat -s CellAlertHookPoc
```

Measured on Android 14 (API 34, userdebug), logged with tag `CellAlertHookPoc`:

| Probe | Without the grant | After `pm grant` |
|---|---|---|
| `content://cellbroadcasts/history` | `SecurityException` | Readable |
| `content://cellbroadcasts` (full table) | `SecurityException` | `SecurityException`: needs a signature permission |
| `pm grant ... RECEIVE_EMERGENCY_BROADCAST` | Refused: `not a changeable permission type` | Refused |
| Manifest and dynamic receivers for the emergency broadcast | Never called | Never called |
| Content observer | Registers | Registers, and fires on a new alert |

After an injected two-page Arabic PDU on channel 4370, the app read the full reassembled text along with `service_category`, `serial_number`, `plmn`, `lac`, `cid`, `language`, `priority` and `cmas_message_class`.

It also lists every alert in the history on screen, which is what [`tools/check-phone.js`](../tools/check-phone.js) uses on a real phone.

The observer also registered without the permission. Whether it would fire in that state was not tested, and the app does not rely on it.

The production implementation is [CellBroadcastProviderDetector](../app/src/main/java/app/cellalerthook/detection/CellBroadcastProviderDetector.kt).
