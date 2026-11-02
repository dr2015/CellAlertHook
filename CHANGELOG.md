# Changelog

## 1.0.0 (2026-11-02)

First public release.

- Reads ETWS and CMAS alerts from Android's Cell Broadcast store after a one-time adb grant.
- Calls an on URL when an alert contains a threat phrase, and an off URL when it contains an all-clear phrase.
- Threat and all-clear phrases, one per line, in **Settings**.
- Sends only the newest command, drops triggers older than the age limit, and retries failed calls with backoff.
- Fires while the phone is locked, and before the first unlock after a restart.
- Optional active days and hours for the on URL.
- App lock with a PIN, hidden and encrypted links, and **Protect from closing**.
- **Check connection**, which sends no request, and manual **Call on URL** and **Call off URL** buttons.
- Support for self-signed servers.
- Preset builds with the links sealed inside the APK.
- Tools for provisioning a phone, checking a phone and testing on emulators, and a test receiver.
