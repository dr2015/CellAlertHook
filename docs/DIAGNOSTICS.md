# Diagnostics

Start at the **Dashboard**, then open **Diagnostics** if something is wrong. **Diagnostics** shows identifiers, counts and outcomes. It never shows text from other apps, and **Share report** contains no alert text.

## Dashboard states

| State | Meaning | What to do |
|---|---|---|
| Ready | The Cell Broadcast provider is active and the URL trigger is **Armed**. | Nothing. |
| Degraded: reading not active | The provider is not active. Alerts can still be caught from the screen, but that text is unverified, so the URL trigger cannot fire. | Run `provision-device.js`. |
| Degraded: not armed | Alerts are detected, but no URL will be called. | Turn on **Armed** when you are ready. |
| Degraded: URL not responding | The newest call failed, and the app keeps retrying. | See [URL trigger problems](#url-trigger-problems). |
| Degraded: server unreachable | Only with an event log server set. Alerts are detected and queued for it. | Run **Diagnostics > Connection test**. |
| Not ready | No detection method is active. **This phone would miss an alert.** | Run `provision-device.js`. |

## Last command

The **URL trigger** card on the **Dashboard** shows the newest command and its status.

| Status | Meaning |
|---|---|
| Sending | Waiting to be sent, or retrying. **Last error** says why. |
| Sent | The URL answered with a status from 200 to 399. |
| Replaced by a newer alert | A newer command took its place, or the alert was found after a newer one. |
| Already in this state | The same command was the last one sent, so nothing was called. |
| Too old, not sent | The alert passed the age limit. |
| Outside active hours, not sent | Active hours are on, and they would not start within the age limit. |

## URL trigger problems

**Check connection** shows `Could not connect` and a reason.

| Reason | Likely cause | Check |
|---|---|---|
| `no_link` | A link is not saved | Paste both links and press **Save**. **Check connection** uses saved links. |
| `SSLHandshakeException` | The server's certificate is not trusted | For a self-signed server, paste its certificate in **Private CA certificate**, then press **Save**. For a public certificate, make sure that field is empty. |
| `IllegalStateException` | The certificate does not cover the address in the link | Check the certificate's Subject Alternative Name. |
| `UnknownHostException` | The name does not resolve on this network | Check the Wi-Fi network and the address. |
| `ConnectException`, `SocketTimeoutException` | Wrong port, a firewall, or client isolation on the access point | Try from a laptop on the same network. |

**Check connection** can pass while a call still fails, because it sends no request. **Last error** on the **Dashboard** shows what happened to a real call.

| Last error | Meaning |
|---|---|
| `url_not_set` | The link is missing. The command waits for it. |
| `http_<code>` | The URL answered with this status. Check the link's path and token, and the request method. |
| `network:<reason>` | No answer came back. The app retries. |
| `client:<reason>` | The request could not be made, for example from a damaged certificate. The app retries. |
| `too_old_to_send` | The trigger passed the age limit while it was retrying. |

A real alert that fired nothing can be explained in **Diagnostics > Recent log**. Each alert has a `decision` line with one of these results.

| Result | Meaning |
|---|---|
| `ACTIVATE`, `CLEAR` | A command was made. |
| `NOT_ARMED` | **Armed** is off. |
| `NOT_FROM_CELL_BROADCAST_STACK` | The text came from the screen or a notification. |
| `TEST_OR_EXERCISE` | A test, exercise or unknown channel. See [Which channels can fire](URL_TRIGGER.md#which-channels-can-fire). |
| `NO_RULE_MATCHED` | No phrase matched. Add the wording to the phrases. |
| `BOTH_RULES_MATCHED` | The text matched a threat phrase and an all-clear phrase. |
| `SIMULATED` | A simulation, which never fires. |

## Cell Broadcast capability

| Line | Healthy value | If not |
|---|---|---|
| Verdict | Direct read available | "Provider present, permission missing": grant it. "No readable provider": this phone cannot use the app. Use a different model. |
| Read access | `dangerous, granted=true` | Run `provision-device.js`. If the grant is refused, see the OEM notes in [SETUP.md](SETUP.md). |
| Direct broadcast | `signature\|privileged, granted=false` | This is normal. Android never grants it to an ordinary app. |
| Provider | `com.google.android.cellbroadcastservice` or an OEM package | "not present" means the OEM replaced the stack. |
| `/history` | `READABLE rows=N` | `DENIED` means the grant is missing. |
| Full table | `DENIED` | This is normal. It needs a signature permission. |
| Protected broadcasts seen | `0` | Anything else is unexpected and worth reporting, because it would mean this device behaves differently from AOSP. |
| SIM / network | The code of your local network, such as `420...` | Without a local network the phone receives nothing. |

## Evidence by capability

This card in **Diagnostics** counts what this phone has seen. Each row is a separate capability and each column a separate kind of proof.

| Column | Means |
|---|---|
| Simulated | The app's own test tools. This proves app logic only. |
| Lab build | The real pipeline on a debuggable build, where a test PDU may have been injected. |
| Live device | The real pipeline on a retail build. |

**Real government alert** counts only a provider row seen on a retail build. A simulated pass can never reach that row.

## Common problems

| Symptom | Likely cause | Check |
|---|---|---|
| Accessibility shows "Needs setup" after working before | The app was force-stopped, by a person or an OEM cleaner | Re-run `provision-device.js`. Disable the OEM cleaner for this app. |
| Provider shows "Needs setup" after months | Permission auto-revoke, or the app was reinstalled | Re-run `provision-device.js`. |

These apply only with an event log server set.

| Symptom | Likely cause | Check |
|---|---|---|
| Connection test fails at `network` | Wi-Fi is off or not associated | Check the Wi-Fi connection. |
| Connection test fails at `tcp_connect` | Wrong port, a firewall, or client isolation on the access point | Try from a laptop on the same network. |
| Fails at `tls_and_health` with an SSL error | Untrusted CA, or the certificate does not list this host | Paste the CA into **Settings**. Check the Subject Alternative Name. |
| `Credential rejected` | Wrong or rotated token | Enter the token again. Events stay queued and are sent once it is fixed. |
| Pending count keeps growing | The server is down or rejecting events | See **Server responses** in **Diagnostics**. |
| Event marked Rejected | The server answered with a 4xx that retrying cannot fix | See **Last error** on the alert, and fix the server or payload. |
| Event marked Blocked | A simulation would have used the production path | Set a separate test endpoint. |

## Logs from a computer

```bash
adb logcat -s CellAlertHookReceiver
```

Log lines hold identifiers and outcomes. Set **Settings > Logging level** to `DEBUG` to include each accessibility window event, by package and class name only.

Check what the system stack holds, using the same provider the app reads:

```bash
adb shell content query --uri content://cellbroadcasts/history --projection _id:service_category:serial_number:received_time:message_displayed
```

Check service state:

```bash
adb shell dumpsys activity services app.cellalerthook
```

## After a failed real-alert test

Collect these before anything is cleared, because the history row and logs are the best record of where the chain broke:

1. **Diagnostics > Share report**.
2. The `content query` output above.
3. `adb logcat -d -b main -b radio > logcat.txt`, taken as soon as possible, since the log buffer is short.
4. A note of what the phone showed: the dialog, the time, and whether sound played.

Then compare:

| Phone showed the alert? | Row in `/history`? | Conclusion |
|---|---|---|
| Yes | Yes, but no event in the app | App problem. Check the grant and the service state. |
| Yes | No | The OEM uses a different store. Accessibility can only record the alert on this model, so the URL trigger cannot fire. Use a different model. |
| No | No | Nothing reached the phone. Check coverage, SIM and alert settings. This is not an app failure. |
