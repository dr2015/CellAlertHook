# Security

## Reporting a problem

Please report security problems privately. Do not open a public issue.

Use GitHub's private vulnerability reporting: open the [Security tab](https://github.com/dr2015/CellAlertHook/security) and choose **Report a vulnerability**, or go straight to the [report form](https://github.com/dr2015/CellAlertHook/security/advisories/new).

Say which app version and Android version you used, what you did, and what happened. Do not include real links, tokens or PINs.

## Threat model

| Area | What the app does |
|---|---|
| Alert source | It trusts Android's Cell Broadcast stack and nothing else. Text from the screen or notifications never fires. |
| Saved links | Links often contain a key. They are encrypted with an Android Keystore key, hidden while typed, never shown again and never written to the log. |
| Someone holding the phone | The PIN guards the app's screens. **Protect from closing** stops casual closing. The phone's own screen lock guards everything else. |
| A copied preset APK | The links inside are encrypted with the setup PIN (PBKDF2 and AES-256-GCM), and the PIN is not in the APK. |
| The network | `https` works for any address. `http` is accepted only for a private address. Hostname checks stay on, even with a pasted certificate. |

## Known limits

**Alerts**

- Cell Broadcast is not signed. A fake base station nearby can send text that matches your phrases, and the URL will be called. Nothing in the app can stop this.
- There is no person in the loop. Once **Armed**, the app acts by itself.
- A drill sent on a real alert channel with matching words fires the URL.
- A live alert reaching the app on a real phone and firing a real system has not been proven.

**The phone**

- Someone past the phone's screen lock can remove **Protect from closing** in a few taps on Android's page.
- Powering off and turning off Wi-Fi cannot be stopped, and nobody is told.
- USB debugging lets a computer force stop the app. Turn it off after setup.
- Full blocking needs a dedicated phone set up as device owner, which needs a factory reset.

**Links and builds**

- Someone with a preset APK can guess the setup PIN offline, with no wait between guesses. Use a long PIN, and share the APK and the PIN separately.
- A link on `http` crosses the network unencrypted, so anyone able to watch that network can read it and any token in it.
- Debug builds have test commands that can call the saved URLs. Use a release build on any phone that holds real links.

**Android**

- `READ_CELL_BROADCASTS` is a hidden permission with no compatibility promise. An Android or Google Play system update could close this route. See [COMPATIBILITY.md](docs/COMPATIBILITY.md#dependence-on-a-hidden-permission).
