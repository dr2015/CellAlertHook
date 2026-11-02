# Hardware verification

This is the procedure for a stock phone with a SIM. It produces the proof emulators cannot: a real over-the-air alert reaches the app and calls a URL.

## What is left to prove

| Link in the chain | Status |
|---|---|
| Cell tower to phone modem | **Shown on one phone,** which received 38 real alerts. |
| Modem to Android telephony stack | **Shown on that phone.** |
| Android stack to the system provider | **Shown on that phone.** |
| Provider to an ordinary app | **Shown on that phone**, by the viewer and by the full app, reading stored alerts. |
| Real alert text through the rules to a test URL | **Shown on that phone**, in about 0.2 s, screen off and locked. **This was stored text, not a live alert.** |
| App catching a live alert on a real phone | **Not tested.** Needs a real broadcast. |
| A live alert firing a real system | **Not tested.** Tested on emulators with injected alerts. |
| The same behaviour on other phone brands | **Not tested.** OEMs can change the stack. |

Parts A and B below can be run today. Part C needs a real broadcast.

## Quick first check: alerts already on the phone

If the phone has received emergency alerts before, this may prove the radio-to-app chain at once. It uses the small viewer in `poc/`, so [build it first](../poc/README.md). Then turn on USB debugging, connect the phone, accept the prompt on its screen, and run:

```bash
node tools/check-phone.js --apk <path to the viewer APK from the Releases page>
```

With several phones attached, add `--device <serial>`.

It installs the viewer, grants it the one Cell Broadcast permission, and lists what an ordinary app can read from the phone's alert history, on the terminal and on the phone's screen. It also saves the result to a file on the computer. It sends nothing anywhere and changes no alerts. Remove the viewer afterwards with `adb uninstall app.cellalerthook.viewer`.

| Result | Meaning |
|---|---|
| `YES`, on a retail build | **Real proof for this phone.** These alerts came from the network, and an ordinary app read them. |
| `ACCESS WORKS, NOTHING STORED` | The route is open, but this history is empty. **This is not a failure.** The list in the phone's Settings is kept in a separate store that apps cannot read, so old alerts there may not appear here. The next real alert decides it. |
| `BLOCKED` | The phone refused the grant or the read. Only on-screen capture may work here, and that cannot fire a URL. |
| `NOT POSSIBLE THIS WAY` | This phone has no standard alert history. |

This shows that an app can **read** alerts. It does not show that the app receives a new one in the background, which is what Part C is for.

## Restart test

This shows whether the phone can work after a restart with a screen lock, before anyone unlocks it. It needs the test receiver from [URL_TRIGGER.md](URL_TRIGGER.md#testing-without-the-system-you-control) and a debug build.

Samsung phones switch USB debugging off until the first unlock, so the phone reports by itself:

1. Set the report address, using the computer's address:

   ```bash
   adb shell am broadcast -n app.cellalerthook/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION --es boot_probe_url http://192.168.1.50:8787/hook/boot-probe
   ```

2. Do a full restart. Locking the screen is not enough. **Do not unlock it.**
3. Wait about 3 minutes, then open `http://localhost:8787/hook-log` on the computer.

Look for `/hook/boot-probe` lines with `unlocked=false`. They show that the app started, how many alerts it read (`rows`), how the rules sorted them, and how many URLs it could read (`urls`, which should be 2). Reaching the computer shows the network works. If there are no lines, use no screen lock on that phone.

## Getting a legitimate broadcast

**Never transmit a Cell Broadcast yourself.** Using a software-defined radio or a fake base station to send one is illegal without authorization, and can reach the public.

The legitimate options are:

1. **An official national test.** Many countries run them and announce them in advance. Follow your national alert authority and plan around them.
2. **Your mobile operator.** Ask whether a test can be arranged for your cell, or when the next one is due.
3. **A licensed test lab.** Some operators and device labs have shielded rooms with a licensed network simulator.

A real alert also counts as a valid result, as long as the procedure is ready to record it.

## Plan around one chance

An official broadcast cannot be repeated on request, and one phone can only be in one state at a time. So **prepare several phones of the same model if you can, each in a different state**, and test the others with Part A and B beforehand.

| Phone | State during the broadcast |
|---|---|
| 1 | App visible |
| 2 | App in background, screen on |
| 3 | Screen off, locked by timeout |
| 4 | Rebooted shortly before, UI never opened |
| 5 | Wi-Fi off during the broadcast, then turned back on |

One phone still gives a valid result. Use state 3, since that is how the phone will normally sit.

**Point the URLs at the [test receiver](../mock-server/README.md) or at something harmless.** Then a real alert cannot start the system you control while you are testing.

## Part A: setup check

Run on each phone. No broadcast is needed.

| # | Step | Pass |
|---|---|---|
| A1 | Run `provision-device.js` | Exits 0 and reports `READ_CELL_BROADCASTS granted: true` |
| A2 | Save both URLs, then press **Settings > Check connection** | "The server answered" |
| A3 | Read **Diagnostics > Cell Broadcast capability** | "Direct read available" |
| A4 | Check the same screen | **SIM / network** shows your local network code |
| A5 | Check **Diagnostics > Emergency alert packages** | The system alert package is listed as `system` |
| A6 | Check the phone's emergency alert settings | Alerts on, and test alerts on if offered |
| A7 | Turn on **Armed**, press **Save**, and check the **Dashboard** | **Ready** |

**If A3 says "No readable provider", stop and try a different model.** This is the most useful single check. It takes a minute and needs no broadcast.

Record: manufacturer, model, Android version, security patch, SIM operator.

## Part B: behaviour without a broadcast

These use the optional event log, with Test C and heartbeats, to prove the app's side of each condition. Point **Event log server** at the [test receiver](../mock-server/README.md). Each simulated event must appear on the **test** endpoint only.

| # | Condition | Steps | Pass |
|---|---|---|---|
| B1 | App visible | Run Test C from **Diagnostics** | Delivered |
| B2 | App in background | Press Home and wait 5 minutes | Heartbeats continue on the server, and no restart appears in **Diagnostics > Recent log** |
| B3 | Screen locked | Lock the phone, wait 5 minutes, then unlock and check that the service never stopped | Service **Running**, and no gap in server heartbeats |
| B4 | Screen off and idle | Leave it unplugged and untouched for 2 hours or more | No gap in heartbeats. **Dashboard** still **Ready**. |
| B5 | After reboot | Reboot and do not open the app | A heartbeat arrives within about 2 minutes of boot |
| B6 | Wi-Fi loss | Turn Wi-Fi off, turn mobile data off, and run Test C | Alert shows **Pending**, pending count 1 |
| B7 | Reconnection | Turn Wi-Fi on | Delivered once, within about 5 minutes, no duplicate on the server |
| B8 | Isolated Wi-Fi | Use a Wi-Fi network with no internet, and leave mobile data on | **Check connection** passes and delivery works |

B8 matters because Android moves its default route to mobile data when Wi-Fi has no internet. The app binds to Wi-Fi to work around that, but this has only been run on an emulator.

## Part C: live broadcast

Before the broadcast:

- [ ] Every phone reads **Ready**, on charge unless testing idle behaviour.
- [ ] The test receiver is running and its clock is correct.
- [ ] A person is ready to note the time and record each screen.
- [ ] Do not dismiss the alert on the phone until you have recorded the screen.

When it arrives, record:

| Field | Value |
|---|---|
| Time seen on the phone | |
| Phone state | |
| Dialog shown? Sound played? | |
| Text as shown, in each language | |

Then, for each phone:

| # | Check | Pass |
|---|---|---|
| C1 | **Alerts** has the alert | Present, with no SIMULATED badge |
| C2 | **Captured by** | `cell_broadcast_provider` |
| C3 | **Source** and **Validation** | `android_cell_broadcast` and `cell_broadcast_stack` |
| C4 | The alert's text | Exactly matches the phone's screen |
| C5 | **Message ID** and **Network (PLMN)** | Present. Note the channel. |
| C6 | **Alert class** | An official test should show as a test channel, and should call no URL. If a drill arrives on a real alert channel, **record that**, because the app then cannot tell a drill from a real alert by channel alone, and matching words will fire the URL. |
| C7 | `/hook-log` on the test receiver | For a real alert with matching words, one call to `/hook/on`, or `/hook/off` for an all-clear. Otherwise none. |
| C8 | **Dashboard** | **Last command** matches C7. |
| C9 | Delay | Time of the call minus the time on the phone. Note the value. |
| C10 | **Diagnostics > Evidence by capability** | **Real government alert** now counts 1 under **Live device**. |
| C11 | Accessibility | The alert shows `accessibility` under **Also seen by**. This confirms the backup on this OEM. |

## Part D: reading the result

| Result | Meaning | Next step |
|---|---|---|
| C1 to C5 and C7 pass on a real alert | **Live capture and URL call proven on this model.** | Repeat for each model you use. Then test the real URLs, with people warned. |
| Dialog appeared, but no provider event, while accessibility captured it | This OEM uses another store, or blocks the provider. Accessibility can only record the alert, so the URL trigger cannot fire. | Change model. |
| Dialog appeared and nothing was captured | Neither method works on this model. | Change model. See the table at the end of [DIAGNOSTICS.md](DIAGNOSTICS.md#after-a-failed-real-alert-test). |
| No dialog appeared | The alert did not reach this phone. It is not an app result. | Check coverage, the SIM and alert settings. Compare with a personal phone nearby. |

Collect the items listed under [After a failed real-alert test](DIAGNOSTICS.md#after-a-failed-real-alert-test) for anything other than a clean pass.

## Records to keep

Keep these together for each phone and each test:

- Diagnostics report (**Share report**)
- The test receiver's call list
- A photo of the phone's alert
- The `content query` output from [DIAGNOSTICS.md](DIAGNOSTICS.md#logs-from-a-computer)
- The completed tables above

A simulated pass must never be filed as a real-world result. The app keeps them apart in **Evidence by capability**, and these records should as well.
