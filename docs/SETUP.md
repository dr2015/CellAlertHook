# Setup

## What you need

| Item | Requirement |
|---|---|
| Phone | Android 11 or newer. A dedicated phone is best. See [COMPATIBILITY.md](COMPATIBILITY.md) for what has been tested. |
| SIM | Active SIM with radio coverage. Cell Broadcast does not need a data plan. |
| Network | A network the phone can use to reach both URLs. It needs internet access only if the URLs are on the internet. |
| Computer | Used once, with USB. [Platform tools](https://developer.android.com/tools/releases/platform-tools) (`adb`) and Node.js 20 or newer. |
| The system you control | Two URLs: one that turns it on and one that turns it off. No server is needed. |

No root, no custom Android build and no OEM signing are involved.

The tools look for `adb` in the `platform-tools` folder under `ANDROID_HOME` or `ANDROID_SDK_ROOT`, and then under the default Android SDK folder.

## 1. Prepare the phone

1. Connect it to the mobile network and to the Wi-Fi that reaches your URLs.
2. Decide on a screen lock. See [Screen lock](#screen-lock).
3. Enable USB debugging: Settings > About phone > tap **Build number** seven times, then Settings > Developer options > **USB debugging**.
4. Connect by USB and accept the debugging prompt on the phone.

Xiaomi, Redmi and POCO phones also need **USB debugging (Security settings)** turned on in Developer options, or `pm grant` is refused. Some other OEMs have a similar switch.

## 2. Install and grant

Download the APK from the [Releases page](https://github.com/dr2015/CellAlertHook/releases), then run:

```bash
node tools/provision-device.js --apk <path to the APK>
```

Use a release build on any phone that will hold real links. The debug build has test commands that can call them.

With several devices attached, add `--device <serial>` (from `adb devices`).

This one command does the whole job:

| Step | Why |
|---|---|
| `adb install` | Android allowlists restricted permissions for adb installs. An APK opened from a file manager or browser cannot hold `READ_CELL_BROADCASTS`. |
| Grant `READ_CELL_BROADCASTS` | How the app reads alerts. Android has no on-screen control for this permission. |
| Allow notifications | Shows that the receiver is running |
| Enable notification access | Backup detection, and it also makes Android restart the app after a force-stop |
| Enable the accessibility service | Backup detection of the alert window |
| Exempt from battery optimization | Keeps the receiver working while the phone is idle |
| Disable permission auto-revoke | Otherwise Android can remove the grant after months with the app unopened, which is normal on a dedicated phone |
| Allow background running | Keeps the receiver working in the background |

Backup detection only records what it sees. It can never fire a URL.

**Do these through the script, not the Settings screens.** Android 13 and later can show "Restricted setting" for apps that were not installed from an app store. On Android 15 and later the same protection covers `READ_CELL_BROADCASTS`, accessibility and notification access. Because the script works through adb, none of those screens are involved.

If the phone was already set up by hand and shows "Restricted setting", open Settings > Apps > CellAlertHook, then the three-dot menu > **Allow restricted settings**.

Afterwards, turn USB debugging off. The grants stay in place.

## 3. Set up the URL trigger

Open the app and create a PIN of 6 to 12 digits. On the **Dashboard**, turn on **Protect from closing** under **Setup needed**. Then go to **Settings**.

| Setting | Value |
|---|---|
| Armed | Off until you go live. No alert calls anything while it is off. |
| URL to call when a threat is announced | The on URL. Use `https`, or `http` on a private address such as `192.168.1.50`. It stays hidden once saved. |
| URL to call when the threat has passed | The off URL |
| Request method | GET or POST, whichever the system you control expects. The default is GET. |
| Threat and all-clear phrases | One per line. **The defaults come from real alerts in one country, so set your own.** See [Phrases](URL_TRIGGER.md#phrases). |
| Do not trigger for alerts older than | The age limit, in minutes. The default is 30. |
| Active days and hours | Off by default. See [Active days and hours](URL_TRIGGER.md#active-days-and-hours). |
| Private CA certificate | Only for a self-signed server. See [Self-signed servers](URL_TRIGGER.md#self-signed-servers). |

With a [preset build](URL_TRIGGER.md#preset-builds) the links are already inside. Otherwise paste them here.

Press **Save**, then **Check connection**, which sends no request. To go live, turn on **Armed** and press **Save** again.

**Call on URL** and **Call off URL** on the **Dashboard** call the real URLs, even with **Armed** off and outside active hours. Warn people first.

## 4. Confirm it is ready

The **Dashboard** must show **Ready**. If it shows **Degraded** or **Not ready**, the text says why, and **Setup needed** lists what is missing.

**Dashboard > Real-world proof** stays at "Not demonstrated" until a real broadcast is received. That is correct. See [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md).

## Phone settings that matter

- **Emergency alert settings:** check that alerts are on in the phone's Wireless Emergency Alerts settings. If the phone offers a test alerts option, turn it on, because required monthly tests are not always on by default.
- **Mobile data:** can be off. Cell Broadcast does not use it. While Wi-Fi is connected the app sends over Wi-Fi, even if Android treats that Wi-Fi as having no internet. With no Wi-Fi, it uses the phone's default network.
- **Power:** keep the phone on a charger.
- **Advanced Protection (Android 17):** leave it off on this phone. It limits accessibility to apps classed as accessibility tools, which this app does not claim to be.
- **OEM battery managers:** Samsung "Sleeping apps", Xiaomi "Battery saver" and similar features need the app set to unrestricted. On Samsung use Settings > Apps > CellAlertHook > Battery > Unrestricted. On Xiaomi also turn on **Autostart**.

## Screen lock

**Set one if other people can reach the phone.** It is what stops someone removing the app's protection, because Android does not let the app guard that page. See [what the lock cannot stop](URL_TRIGGER.md#what-the-lock-cannot-stop).

After a restart with a PIN, pattern or password, Android keeps most of the phone encrypted until someone unlocks it. The app is built to run in that state, as Android's own alert system does. It keeps its data in device-protected storage and starts at `LOCKED_BOOT_COMPLETED`. On an Android 15 emulator it fired before the first unlock.

One retail phone passed the same checks: the app started by itself, read its alerts and URLs, and reached the network over Wi-Fi, all before any unlock. No alert arrived in that test. Other phones or networks may differ. A network can depend on data that is still encrypted, and a URL on a Wi-Fi network cannot be reached with no Wi-Fi. The app retries when the network returns.

Without a screen lock, Android unlocks everything at boot, so none of this applies, but the phone must then be kept physically secure.

## Do not force-stop the app

**Protect from closing** greys this out in Android's Settings. Force-stopping makes Android **remove the accessibility service from the enabled list**. The app restarts itself and keeps working through the Cell Broadcast provider, but accessibility stays off until it is re-enabled. The **Dashboard** shows this as **Needs setup**. Running `provision-device.js` again restores it.

## Build a signed release

Point `CELLALERTHOOK_SIGNING_FILE` at a properties file holding `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then run:

```bash
./gradlew :app:assembleRelease
```

Without that file, the release build is unsigned. Keep the key safe, because an update must use the same key.

## Before you rely on it

- [ ] Use a release build, not a debug build.
- [ ] Turn off USB debugging once setup is done.
- [ ] Read [what can still go wrong](URL_TRIGGER.md#what-can-still-go-wrong). There is no person in the loop, and a broadcast cannot be authenticated.
- [ ] Make someone responsible for checking the **Dashboard** regularly. With no server, nobody is told if the phone stops working.
- [ ] Ask your national alert authority or mobile operator how drills are sent, and check that your phrases will not match them.
- [ ] After any real alert, check **Alerts**, and add any new wording to the phrases.
- [ ] Run the procedure in [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md) on the exact phone model you will use.

The optional event log server is set up in the same **Settings** screen. Leave its address blank if you do not use one.
