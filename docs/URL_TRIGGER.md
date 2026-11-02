# URL trigger

The app calls one URL when a threat is announced, and another when the threat has passed. No server is needed.

## What happens for each alert

| Alert | What the app does |
|---|---|
| Contains a threat phrase | Calls the **on** URL |
| Contains an all-clear phrase | Calls the **off** URL |
| The same command again, with nothing between | Records it, calls nothing more |
| Contains both kinds of phrase | Records it, calls nothing |
| Anything else | Records it, calls nothing |

Nothing is called unless **Armed** is on.

## Phrases

Phrases are in **Settings**, one per line. A message containing any threat phrase is a threat. A message containing any all-clear phrase is an all-clear. Warnings and all-clears can arrive on the same channels, so only the words tell them apart.

Matching ignores upper and lower case, repeated spaces and Arabic vowel marks. Each phrase needs at least 6 characters, because a very short phrase would match nearly every alert. To arm, you need at least one phrase of each kind.

**The defaults come from real alerts in one country, so anyone elsewhere must set phrases for their own country.**

| Kind | Default phrases |
|---|---|
| Threat | `لتهديد جوي معادي`, `hostile aerial threat` |
| All-clear | `زال الخطر`, `threat has passed` |

**A warning worded differently will not fire.** If the authorities use new wording, for example for another kind of threat, the alert is recorded but nothing is called. Check **Alerts** after any real alert, and add new phrases when you see them.

## Which channels can fire

The app classifies each alert from its 3GPP message identifier. Channels that are not known to be real alerts never fire, even with the right words.

| Channels | Meaning | Can fire? |
|---|---|---|
| 4352, 4353, 4354, 4356 | ETWS earthquake, tsunami and other | Yes |
| 4355 | ETWS test | No |
| 4370 to 4379, 4383 to 4392 | CMAS national, extreme, severe and AMBER | Yes |
| 4380, 4393 | Required monthly test | No |
| 4381, 4394 | Exercise | No |
| 4382, 4395 | Operator defined | No |
| 4396, 4397 | Public safety | Yes |
| 4398, 4399 | State or local test | No |
| Any other channel | Unknown | No |

Some countries send alerts on channels outside this range. On those channels the app records the alert, and nothing fires. Check **Message ID** in **Alerts** after a real alert to see which channel yours use.

## Defaults on a new install

| Setting | Default |
|---|---|
| Armed | Off |
| Links | None |
| Certificate | None |
| Request method | GET |
| Age limit | 30 minutes |
| Active hours | Off |
| Threat and all-clear phrases | Taken from real alerts in one country |

**A normal build has no links.** They often contain a token, and anyone holding an APK can read what is in it. Paste them on the phone, or use a [preset build](#preset-builds) with them sealed inside.

## Setup

1. Open the app and create a PIN.
2. On the **Dashboard**, work through **Setup needed**, including **Protect from closing**.
3. In **Settings > URL trigger**, paste both links with **Armed** off, then press **Save**.
4. Press **Check connection**. It uses the saved links. It connects, checks TLS and hangs up. It sends no request, so it cannot fire anything. It also cannot tell whether a link's path or token is right.
5. When you are ready to go live, turn on **Armed** and press **Save**.

A new install is never armed, so the app cannot call anything until someone arms it.

**Call on URL and Call off URL call the real links**, even with **Armed** off and outside active hours. Warn people first. They are how you test the links, and how you stop the system you control if an all-clear never comes.

## How the call is made

| Item | Behaviour |
|---|---|
| Method | GET or POST. POST sends an empty body. |
| Success | Any answer from 200 to 399. Redirects are not followed. |
| Failure | Any other answer, or no answer. The call retries with backoff. |
| Addresses | `https` works for any address. `http` is accepted only for a private address such as `192.168.1.50`. |
| Network | While Wi-Fi is connected, calls go over Wi-Fi, even if Android prefers mobile data. With no Wi-Fi, they use the phone's default network. |
| Storage | URLs are stored encrypted, because they often contain a key, and they are never written to the log. |

## Self-signed servers

Paste the server's certificate in **Settings**, in the field **Private CA certificate**, or give it to the [preset build tool](#preset-builds). That field is in the **Event log server** section, but it applies to the on and off URLs as well.

Hostname checks stay on, so the certificate must cover the address used.

**While this field is filled in, the app trusts only that certificate.** A URL with a normal public certificate will then fail. If you change the server's certificate, every call fails until the new one is pasted in. **Check connection** shows this failure.

## Active days and hours

These are **off by default**. Turn them on in **Settings > Active days and hours**, and choose the days and times. The form starts at Monday to Friday, 08:00 to 17:00. They use the phone's own clock.

| When | What the app does |
|---|---|
| Threat during active hours | Calls the on URL |
| Threat outside active hours | Records it, and calls nothing |
| Threat within the age limit before the active hours start, with no all-clear yet | Waits, and calls the on URL when they start |
| All-clear at any time | Calls the off URL. Active hours limit only the on URL. |
| **Call on URL** and **Call off URL** buttons | Work at any time |

The age limit is the one in **Settings > URL trigger**.

**Holidays are not covered.** Turn **Armed** off for days when nothing should fire.

## Preset builds

This makes one APK that holds the links, for several phones. It needs `JAVA_HOME` and `CELLALERTHOOK_SIGNING_FILE`, a properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

```bash
node tools/build-preset-apk.js
```

It asks for both links, a setup PIN of 8 to 12 digits and an optional certificate file. It hides what you paste, encrypts it (PBKDF2 and AES-256-GCM) and builds `dist/CellAlertHook-<version>-preset.apk`. It sends nothing to the links.

| What | How it works |
|---|---|
| The links | Encrypted with the setup PIN. The PIN is not in the APK, so the file alone does not give up the links. |
| First open on each phone | Enter the setup PIN. That opens the links, stores them in that phone's Keystore, and it becomes the app's PIN there. |
| Armed | Still off. Turn it on, on each phone, when that phone goes live. |
| Installing | Over USB once, with `node tools/provision-device.js --apk <file>`. Android does not give the Cell Broadcast permission to an APK opened from a file. |
| A phone that already has a PIN | Uninstall the app first, because the links only open on a first open. |
| A link changes | Build a new APK |

**Use a long PIN, and share the APK and the PIN separately.** Someone with the file can guess PINs on a computer, with no wait between guesses, so 12 digits is much safer than 8.

## App lock

| Protection | What it does |
|---|---|
| PIN | Needed every time the app is opened, from 6 to 12 digits. Five wrong tries start a 30 second wait, which doubles up to an hour and survives a restart. |
| Links | Hidden while typed and never shown again, even with the PIN. Encrypted with an Android Keystore key. Paste over one to replace it. |
| Screens | `FLAG_SECURE` blocks screenshots, screen sharing and the recent apps preview. |
| Protect from closing | A device admin with no policies, turned on by you on Android's consent screen. It greys out **Force stop** and **Clear storage**. **Uninstall** stops at Android's own page, which must remove the protection first. It cannot lock or wipe the phone. |
| Release build | Has no test commands, and nobody can read its files over USB |

The lock covers the screens only. The receiver runs behind it, so alerts still fire while the app is locked.

### What the lock cannot stop

Android does not let an ordinary app block these:

- **Someone past the phone's own screen lock** can remove the protection in a few taps. That page belongs to Android, so the app's PIN cannot guard it. The phone's screen lock is what stops this.
- **Turning the phone off**, or turning off Wi-Fi or the mobile network. The **Dashboard** shows the result, and nobody is told.
- **A computer with USB debugging on** can force stop the app. Turn USB debugging off after setup.

Full blocking needs a dedicated phone set up as device owner, which needs a factory reset.

**If the PIN is lost**, remove the protection, uninstall the app, install it again with `provision-device.js`, and paste the links again.

## Testing without the system you control

Run the test receiver on a computer on the same network:

```bash
CELLALERTHOOK_TOKEN=test node mock-server/server.js --port 8787
```

Set the phone's URLs to `http://<computer address>:8787/hook/on` and `http://<computer address>:8787/hook/off`. Every call is recorded, and you can read the list at `http://localhost:8787/hook-log` on that computer. Nothing reaches the system you control, so it is safe to arm the phone this way and wait for a real alert.

A retail phone cannot be sent an alert on demand, so the debug build has a command that sends a real stored alert through the phrase rules, queue and URL call:

```bash
adb shell am broadcast -n app.cellalerthook/.DebugProvisioningReceiver -a app.cellalerthook.debug.REPLAY --es kind threat
```

Use `--es kind clear` for an all-clear. Like the manual buttons, this ignores **Armed** and active hours. It tests everything after the app reads an alert. It does not prove a live alert, and it never counts as one.

**Never run this on a phone holding real links, because it calls them.** The release build does not have this command.

## Rules

| Rule | Why |
|---|---|
| Only an alert from Android's Cell Broadcast stack can fire | Text from the screen or a notification is unverified, so it never fires, whatever it says |
| Test and exercise channels never fire, nor do channels whose purpose is unknown | A drill must not fire the URL |
| Simulations never fire | Test A, B and C cannot reach the URLs |
| Only the newest command can be sent | A trigger delayed by a network failure can never arrive after the all-clear |
| Commands are ordered by when the phone received the alert | If the app was down, an old warning found after a newer all-clear changes nothing |
| A trigger older than the age limit is not sent | The system you control must not start for an alert that is over. The default limit is 30 minutes. |
| An all-clear never expires | Turning off is always safe |
| Failed calls retry with backoff | A trigger stops when it expires or an all-clear replaces it. An all-clear keeps trying. |

## What can still go wrong

**There is no person in the loop, and Cell Broadcast is not signed.** A fake base station nearby could send a message with the right words, and the on URL would be called.

| Risk | What happens | What to do |
|---|---|---|
| The all-clear never arrives | The system you control stays on. The off URL is never called by itself, because that could stop it during a real threat. | Press **Call off URL** |
| A drill uses a real channel and the real words | The URL fires | Ask your national alert authority or mobile operator how drills are sent. Part C of the [hardware procedure](HARDWARE_VERIFICATION.md) records this. |
| The authorities use new wording | Nothing fires | Check **Alerts** after any real alert, and add the new phrase |
| The phone restarts with a screen lock set | The app starts by itself about a minute after the restart. On one retail phone it read its alerts, its URLs and the network before any unlock. It fired before unlock on an emulator, but a live alert in this state is not proven on a real phone. | Run the restart test in [HARDWARE_VERIFICATION.md](HARDWARE_VERIFICATION.md) on your phone |
| The phone is off, or out of signal | Nothing fires, and with no server nobody is told | Keep it on mains power, and check the **Dashboard** regularly |
| The app stops working | With no server there are no heartbeats, so nobody is told | Check the **Dashboard** regularly |
| The network is down | The call retries until the network returns. A trigger stops at the age limit. | Keep the phone and the system you control on a reliable network |

## How fast it is

The app calls the URL as soon as it reads the alert, with no server or person between them.

On an Android 15 emulator, this took about 0.3 s from the alert entering Android to the URL being called. On one retail phone, stored real alert text called test URLs in about 0.2 s with the screen off and locked. That second figure leaves out the time to read a new alert from Android, and it is not a live alert. See [the results](HOW_IT_WORKS.md#results).

## The optional event log

Leave **Event log server** blank in **Settings**, and the phone needs nothing else. If an address is set, the app also sends each alert there as a record. The emulator tests use this to check what the phone saw. The protocol is in the [test receiver guide](../mock-server/README.md).
