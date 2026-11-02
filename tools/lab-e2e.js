#!/usr/bin/env node
'use strict';
/**
 * End-to-end scenarios against a real Android Cell Broadcast stack.
 *
 *   full mode  (userdebug image): injects real PDUs through the framework test hook.
 *   stock mode (retail image): the hook does not exist, so it checks the permission path,
 *              the absence of the hook, and the simulated tools.
 *
 * It needs the mock server running with the same token:
 *   CELLALERTHOOK_TOKEN=lab-device-token-1 node mock-server/server.js --port 8787
 *
 *   node tools/lab-e2e.js --device emulator-5556 [--mode full|stock] [--only S01,S02]
 *
 * Nothing here is over-the-air. See docs/HOW_IT_WORKS.md for what that leaves unproven.
 */

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const { buildPdus, randomSerial } = require('./cb-inject');

const PKG = 'app.cellalerthook';
const SERVICE_A11Y = `${PKG}/${PKG}.detection.AlertAccessibilityService`;
const SERVICE_LISTENER = `${PKG}/${PKG}.detection.AlertNotificationListenerService`;
const CELL_PERMISSION = 'android.permission.READ_CELL_BROADCASTS';
const TEST_ACTION = 'com.android.internal.telephony.gsm.TEST_TRIGGER_CELL_BROADCAST';
const ALERT_APP = 'com.google.android.cellbroadcastreceiver';
const TOKEN = process.env.CELLALERTHOOK_TOKEN || 'lab-device-token-1';
const MOCK = process.env.CELLALERTHOOK_MOCK || 'http://127.0.0.1:8787';
const ON_URL = 'http://10.0.2.2:8787/hook/on';
const OFF_URL = 'http://10.0.2.2:8787/hook/off';
const APK = path.join(__dirname, '..', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');

const args = parseArgs(process.argv.slice(2));
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk');
const ADB = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');

function parseArgs(argv) {
  const options = { device: 'emulator-5556', mode: 'full', only: null, skipSetup: false, out: null };
  for (let i = 0; i < argv.length; i++) {
    switch (argv[i]) {
      case '--device': options.device = argv[++i]; break;
      case '--mode': options.mode = argv[++i]; break;
      case '--only': options.only = argv[++i].split(','); break;
      case '--skip-setup': options.skipSetup = true; break;
      case '--out': options.out = argv[++i]; break;
      default: throw new Error(`Unknown argument ${argv[i]}`);
    }
  }
  return options;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function adb(...commandArgs) {
  return execFileSync(ADB, ['-s', args.device, ...commandArgs], { encoding: 'utf8', timeout: 60000 }).replace(/\r/g, '');
}

function shell(command) {
  return adb('shell', command).trim();
}

function tryShell(command) {
  try {
    return shell(command);
  } catch (error) {
    return `ERROR: ${(error.stderr || error.message).toString().trim()}`;
  }
}

async function mock(method, route, body) {
  for (let attempt = 1; ; attempt++) {
    try {
      const response = await fetch(MOCK + route, {
        method,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${TOKEN}` },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
      return await response.json();
    } catch (error) {
      if (attempt >= 3) throw error;
      await sleep(100);
    }
  }
}

const events = async () => (await mock('GET', '/api/emergency/events')).events;

async function waitFor(what, predicate, timeoutMs, intervalMs = 150) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}`);
    await sleep(intervalMs);
  }
}

const eventWithText = (text) => async () => (await events()).find((event) => event.message.includes(text));

/** Accessibility can read the dialog first, and only the provider row carries a channel. */
const providerEventWithText = (text) => async () => {
  const found = await eventWithText(text)();
  return found?.captureMethod === 'cell_broadcast_provider' ? found : null;
};

function expectEqual(actual, expected, label) {
  if (actual !== expected) throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

function expectTrue(condition, label) {
  if (!condition) throw new Error(label);
}

let injected = 0;
let s12Tag = null;

/**
 * Sends a real PDU through the telephony stack. Each call is a new message unless serial is given.
 * `at` is taken just before the last page goes out, so latency is an upper bound that includes
 * adb and `am` start-up. Taking it after `am broadcast` returns would hide that time and more.
 */
function inject({ id = 4370, language = 'ar', text, serial = randomSerial() }) {
  injected += 1;
  const pdus = buildPdus({ messageId: id, serial, language, text });
  let at;
  for (const pdu of pdus) {
    at = Date.now();
    shell(`am broadcast -a ${TEST_ACTION} --es pdu_string ${pdu.toString('hex').toUpperCase()} --ei phone_id 0`);
  }
  return { at, serial };
}

const label = (tag) => `${tag}-${Date.now().toString(36)}`;
const arabic = (tag) => `تنبيه طوارئ تجريبي من مختبر جوفلو، هذه رسالة اختبار فقط ولا تتطلب أي إجراء من أي شخص. المرجع ${tag}`;
/** Real alert wording, plus a reference. Injected on lab images only. */
const warning = (tag) => `تحذير\nتتعرض المنطقة لتهديد جوي معادي يرجى التصرف بهدوء. مرجع المختبر ${tag}\nWarning\nThe area is under a hostile aerial threat. Lab reference ${tag}`;
const allClear = (tag) => `تنبيه\nزال الخطر. مرجع المختبر ${tag}\nAlert\nThe threat has passed. Lab reference ${tag}`;
/** The four-digit number looks like a one-time code, which is all Android 15+ needs to hide a notification. */
const codeLike = (tag) => `بلاغ تجريبي من مختبر جوفلو للاختبار فقط ولا يتطلب أي إجراء. للاستفسار اتصلوا على 1122 شكرا لكم. المرجع ${tag}`;
const english = (tag) => `CellAlertHook lab test message, no action required. Reference ${tag}`;

function appLog() {
  return adb('logcat', '-d', '-s', 'CellAlertHookReceiver:*', 'AndroidRuntime:E');
}

function clearLog() {
  adb('logcat', '-c');
}

function alertWindowShowing() {
  return shell('dumpsys activity activities').includes('CellBroadcastAlertDialog');
}

/** Presses the real OK button. Force-stopping the alert app would stop later broadcasts reaching it. */
async function dismissAlerts() {
  for (let attempt = 0; attempt < 6; attempt++) {
    const dump = tryShell('uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml');
    const match = dump.match(/resource-id="[^"]*:id\/dismissButton"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
    if (!match) return;
    const [x1, y1, x2, y2] = match.slice(1).map(Number);
    shell(`input tap ${(x1 + x2) >> 1} ${(y1 + y2) >> 1}`);
    await sleep(700);
  }
}

function alertNotification() {
  const dump = shell('dumpsys notification --noredact');
  const start = dump.indexOf(`pkg=${ALERT_APP}`);
  if (start < 0) return null;
  const end = dump.indexOf('NotificationRecord(', start);
  const record = dump.slice(start, end > 0 ? end : undefined);
  return {
    title: record.match(/android\.title=String \((.*)\)/)?.[1],
    hiddenFromListeners: /mSensitiveContent=true/.test(record),
  };
}

/** The alert app shows only a count while two alerts are unread, so scenarios must start with none. */
async function clearUnreadAlerts() {
  for (let attempt = 0; attempt < 4; attempt++) {
    const unread = alertNotification();
    if (!unread) break;
    shell('cmd statusbar expand-notifications');
    await sleep(1500);
    const dump = tryShell('uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml');
    const row = dump.match(new RegExp(`text="${unread.title}"[^>]*bounds="\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]"`));
    if (row) {
      const [x1, y1, x2, y2] = row.slice(1).map(Number);
      shell(`input tap ${(x1 + x2) >> 1} ${(y1 + y2) >> 1}`);
      await sleep(2000);
      await dismissAlerts();
    }
    shell('cmd statusbar collapse');
    await sleep(800);
  }
  expectTrue(!alertNotification(), 'the alert app still has an unread notification');
}

function wake() {
  shell('input keyevent KEYCODE_WAKEUP');
  shell('wm dismiss-keyguard');
}

/** A just-booted device can drop the PIN, so success is read from the storage it unlocks. */
async function firstUnlock(pin) {
  for (let attempt = 1; attempt <= 4; attempt++) {
    shell('input keyevent KEYCODE_WAKEUP');
    shell('input keyevent KEYCODE_MENU');
    await sleep(1500);
    shell(`input text ${pin}`);
    shell('input keyevent KEYCODE_ENTER');
    const opened = await waitFor('storage', () => shell('getprop sys.user.0.ce_available') === 'true', 8000, 300).catch(() => false);
    if (opened) return attempt;
  }
  throw new Error('Could not unlock the device, so the app could not have run');
}

function grantDetection() {
  shell(`pm grant ${PKG} ${CELL_PERMISSION}`);
  shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`);
  shell(`cmd notification allow_listener ${SERVICE_LISTENER}`);
  shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
  shell('settings put secure accessibility_enabled 1');
  shell(`dumpsys deviceidle whitelist +${PKG}`);
}

function launch() {
  shell(`am start -W -n ${PKG}/.ui.MainActivity`);
}

async function provision() {
  shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION ` +
    `--es host 10.0.2.2 --ei port 8787 --ez tls false --es token ${TOKEN} --ei retry_base 2 --ei retry_max 8 --ei heartbeat 20 ` +
    `--es activate_url ${ON_URL} --es clear_url ${OFF_URL} --ez armed true --ez allow_lab_triggers true --ez schedule_enabled false`);
  await sleep(600);
}

function simulate(test, language = 'ar') {
  shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.SIMULATE ` +
    `--es test ${test} --es lang ${language}`);
}

async function waitForHealthyApp() {
  await waitFor('app process', () => !tryShell(`pidof ${PKG}`).startsWith('ERROR') && tryShell(`pidof ${PKG}`).length > 0, 60000, 500);
  await waitFor('foreground service', () => tryShell(`dumpsys activity services ${PKG}`).includes('isForeground=true'), 60000, 500);
}

async function waitForBoot() {
  execFileSync(ADB, ['-s', args.device, 'wait-for-device'], { timeout: 180000 });
  await waitFor('boot', () => tryShell('getprop sys.boot_completed') === '1', 180000, 1000);
}

/** S13 follows S12 on purpose: it needs the permission still revoked. */
const CONTINUES_PREVIOUS = new Set(['S13']);

async function restoreBaseline() {
  wake();
  shell('svc wifi enable');
  shell('svc data enable');
  grantDetection();
  await waitForHealthyApp();
  await dismissAlerts();
  await clearUnreadAlerts();
  shell('input keyevent KEYCODE_HOME');
  await mock('POST', '/__mock/faults', { failNext: 0, dropNext: 0, badAckNext: 0, delayMs: 0 });
}

/** A device that has just booted is still compiling and syncing, and timings would show that instead. */
async function waitUntilSettled() {
  await waitFor('device to settle', () => Number.parseFloat(shell('cat /proc/loadavg')) < 3, 300000, 5000)
    .catch(() => console.log('Device still busy after 5 minutes; timings may be inflated'));
}

async function setup() {
  console.log(`Setting up ${args.device} (${shell('getprop ro.build.fingerprint')})`);
  await waitUntilSettled();
  try {
    adb('uninstall', PKG);
  } catch {
    // Not installed yet.
  }
  adb('install', '-r', APK);
  grantDetection();
  clearLog();
  launch();
  await waitForHealthyApp();
  await provision();
  await waitFor('baseline', () => appLog().includes('baseline_recorded'), 15000, 500);
  shell('input keyevent KEYCODE_HOME');
  await mock('POST', '/__mock/reset', {});
}

const FULL_SCENARIOS = [
  ['S01', 'App visible: multi-page Arabic alert reaches the server verbatim', async () => {
    launch();
    const tag = label('s01');
    const text = arabic(tag);
    const { at, serial } = inject({ text });
    const event = await waitFor('event', eventWithText(tag), 10000);
    expectEqual(event.message, text, 'message');
    expectEqual(event.source, 'android_cell_broadcast', 'source');
    expectEqual(event.captureMethod, 'cell_broadcast_provider', 'captureMethod');
    expectEqual(event.language, 'ar', 'language');
    expectEqual(event.alertClass, 'national_alert', 'alertClass');
    expectEqual(event.testOrExercise, false, 'testOrExercise');
    expectEqual(event.simulation, false, 'simulation');
    expectEqual(event.verified, false, 'verified must stay false on a debuggable image');
    expectEqual(event.verification.status, 'cell_broadcast_stack_lab', 'verification.status');
    expectEqual(event.cellBroadcast.messageId, 4370, 'messageId');
    expectEqual(event.cellBroadcast.serialNumber, serial, 'serialNumber');
    expectEqual(event.route, '/api/emergency/alerts', 'route');
    await waitFor('accessibility corroboration', () => /duplicate event=.* method=accessibility/.test(appLog()), 8000);
    await dismissAlerts();
    return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: `${event.message.length} chars, corroborated by accessibility` };
  }],

  ['S02', 'App in background: English alert', async () => {
    shell('input keyevent KEYCODE_HOME');
    await sleep(1500);
    const tag = label('s02');
    const { at } = inject({ id: 4383, language: 'en', text: english(tag) });
    const event = await waitFor('provider record', providerEventWithText(tag), 10000);
    expectEqual(event.language, 'en', 'language');
    expectEqual(event.alertClass, 'national_alert', 'alertClass');
    await dismissAlerts();
    return { latencyMs: Date.parse(event.receivedByServerAt) - at };
  }],

  ['S03', 'Screen off and PIN locked: alert captured, dialog text read over keyguard', async () => {
    shell('locksettings set-pin 1234');
    shell('settings put secure lock_screen_lock_after_timeout 0');
    try {
      shell('input keyevent KEYCODE_SLEEP');
      await waitFor('screen off', () => shell('dumpsys power').includes('mWakefulness=Asleep'), 8000, 300);
      await sleep(1500);
      expectTrue(shell('dumpsys window').includes('mDreamingLockscreen=true') || shell('dumpsys activity activities').includes('mKeyguardShowing=true') || shell('dumpsys window policy').match(/showing=true/), 'keyguard should be up');
      clearLog();
      const tag = label('s03');
      const { at } = inject({ text: arabic(tag) });
      const event = await waitFor('event', eventWithText(tag), 12000);
      await waitFor('accessibility corroboration', () => /duplicate event=.* method=accessibility/.test(appLog()), 10000);
      const wakefulness = shell('dumpsys power').match(/mWakefulness=(\w+)/)[1];
      await dismissAlerts();
      return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: `screen after alert: ${wakefulness}; accessibility read the dialog over the keyguard` };
    } finally {
      shell('locksettings clear --old 1234');
      wake();
    }
  }],

  ['S04', 'Forced Doze: alert captured and delivered while the device is idle', async () => {
    shell('input keyevent KEYCODE_SLEEP');
    shell('dumpsys battery unplug');
    shell('dumpsys deviceidle force-idle deep');
    try {
      const state = shell('dumpsys deviceidle get deep');
      expectEqual(state, 'IDLE', 'deep idle state');
      const tag = label('s04');
      const { at } = inject({ text: arabic(tag) });
      const event = await waitFor('event', eventWithText(tag), 15000);
      return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: 'deep idle confirmed before injection' };
    } finally {
      shell('dumpsys deviceidle unforce');
      shell('dumpsys battery reset');
      wake();
      await dismissAlerts();
    }
  }],

  ['S05', 'Process killed: alert arriving before restart is still found', async () => {
    const pid = shell(`pidof ${PKG}`);
    shell(`run-as ${PKG} kill -9 ${pid}`);
    const tag = label('s05');
    const { at } = inject({ text: arabic(tag) });
    const event = await waitFor('event', eventWithText(tag), 45000, 500);
    await waitForHealthyApp();
    await dismissAlerts();
    return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: `killed pid ${pid}; catchUp=${event.catchUp}, captureMethod=${event.captureMethod}` };
  }],

  ['S06', 'Force-stop: the notification binding revives the app, and the accessibility loss is visible', async () => {
    shell(`am force-stop ${PKG}`);
    await sleep(500);
    await waitForHealthyApp();
    const tag = label('s06');
    const { at } = inject({ text: arabic(tag) });
    const event = await waitFor('event', eventWithText(tag), 15000);
    expectEqual(event.captureMethod, 'cell_broadcast_provider', 'captureMethod');
    const enabled = shell('settings get secure enabled_accessibility_services');
    expectEqual(enabled, 'null', 'Android removes accessibility from a force-stopped app');
    await waitFor('health reports the loss', async () => {
      const state = await mock('GET', '/__mock/state');
      return state.heartbeats[0]?.detectors.accessibility === 'BLOCKED';
    }, 40000, 1000);
    await dismissAlerts();
    shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
    return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: 'revived by the listener binding; provider kept working; accessibility was disabled by Android and reported as BLOCKED' };
  }],

  ['S06b', 'Fully down with no bound services: alert recovered from the provider at next launch', async () => {
    shell(`cmd notification disallow_listener ${SERVICE_LISTENER}`);
    shell('settings delete secure enabled_accessibility_services');
    await sleep(500);
    shell(`am force-stop ${PKG}`);
    await sleep(1500);
    expectTrue(tryShell(`pidof ${PKG}`).startsWith('ERROR') || tryShell(`pidof ${PKG}`) === '', 'app must be down for this test');
    const tag = label('s06b');
    const text = arabic(tag);
    inject({ text });
    await sleep(4000);
    expectTrue(!(await eventWithText(tag)()), 'nothing can be delivered while the app is down');
    await dismissAlerts();
    launch();
    const event = await waitFor('event', eventWithText(tag), 20000);
    expectEqual(event.message, text, 'message');
    expectEqual(event.captureMethod, 'cell_broadcast_provider', 'captureMethod');
    expectEqual(event.catchUp, true, 'catchUp');
    shell(`cmd notification allow_listener ${SERVICE_LISTENER}`);
    shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
    shell('input keyevent KEYCODE_HOME');
    return { detail: 'alert sent while the app was down, recovered and marked catchUp=true' };
  }],

  ['S06c', 'Arabic and English on separate channels, back to back', async () => {
    const tag = label('s06c');
    inject({ id: 4370, language: 'ar', text: arabic(tag) });
    const { at } = inject({ id: 4383, language: 'en', text: english(tag) });
    const both = await waitFor('both languages', async () => {
      const found = (await events()).filter((event) => event.message.includes(tag));
      return found.length === 2 ? found : null;
    }, 10000);
    await dismissAlerts();
    expectEqual(both.map((event) => event.language).sort().join(), 'ar,en', 'languages');
    return { latencyMs: Math.max(...both.map((event) => Date.parse(event.receivedByServerAt))) - at, detail: 'two events, one per language' };
  }],

  ['S07', 'Network loss: alert queued offline, delivered once on reconnection', async () => {
    shell('svc wifi disable');
    shell('svc data disable');
    await sleep(3000);
    try {
      const tag = label('s07');
      inject({ text: arabic(tag) });
      await sleep(7000);
      expectTrue(!(await eventWithText(tag)()), 'the server must not have the alert while offline');
      await dismissAlerts();
      shell('svc wifi enable');
      shell('svc data enable');
      const reconnected = Date.now();
      const event = await waitFor('delivery after reconnect', eventWithText(tag), 45000, 500);
      expectEqual(event.duplicateDeliveries, 0, 'duplicateDeliveries');
      return { latencyMs: Date.parse(event.receivedByServerAt) - reconnected, detail: 'latency measured from reconnection' };
    } finally {
      shell('svc wifi enable');
      shell('svc data enable');
    }
  }],

  ['S08', 'Server errors: backoff retries until acknowledged', async () => {
    await mock('POST', '/__mock/faults', { failNext: 3, status: 503 });
    const tag = label('s08');
    const { at } = inject({ text: arabic(tag) });
    const event = await waitFor('delivery after 503s', eventWithText(tag), 40000, 300);
    await dismissAlerts();
    const faults = (await mock('GET', '/__mock/state')).faults;
    expectEqual(faults.failNext, 0, 'all injected failures consumed');
    return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: '3 injected 503 responses' };
  }],

  ['S09', 'Lost acknowledgment: retry is idempotent, the server keeps one record', async () => {
    await mock('POST', '/__mock/faults', { badAckNext: 1 });
    const tag = label('s09');
    inject({ text: arabic(tag) });
    const event = await waitFor('duplicate retry', async () => {
      const found = await eventWithText(tag)();
      return found && found.duplicateDeliveries >= 1 ? found : null;
    }, 30000, 300);
    await dismissAlerts();
    expectEqual((await events()).filter((e) => e.message.includes(tag)).length, 1, 'records on server');
    const final = await waitFor('provider-backed record', async () => {
      const found = await eventWithText(tag)();
      return found.verification.status === 'cell_broadcast_stack_lab' ? found : null;
    }, 20000, 300);
    return { detail: `duplicateDeliveries=${event.duplicateDeliveries}, final capture=${final.captureMethod}, revision=${final.revision}` };
  }],

  ['S10', 'Repeated broadcast: same text and message code creates no new incident', async () => {
    const tag = label('s10');
    const text = arabic(tag);
    const serial = randomSerial();
    inject({ text, serial });
    await waitFor('first event', eventWithText(tag), 10000);
    clearLog();
    inject({ text, serial: serial + 1 });
    await waitFor('duplicate audit', () => /duplicate event=.* method=cell_broadcast_provider/.test(appLog()), 10000);
    await sleep(1500);
    await dismissAlerts();
    expectEqual((await events()).filter((e) => e.message.includes(tag)).length, 1, 'records on server');
    return { detail: 'second broadcast audited as a duplicate observation' };
  }],

  ['S10c', 'New message code: identical words are a new alert', async () => {
    const tag = label('s10c');
    const text = arabic(tag);
    const first = randomSerial();
    inject({ text, serial: first });
    await waitFor('first event', eventWithText(tag), 10000);
    await dismissAlerts();
    inject({ text, serial: first ^ 0x10 });
    await waitFor('second event', async () => (await events()).filter((event) => event.message.includes(tag)).length === 2, 10000);
    await dismissAlerts();
    return { detail: 'two events for identical text on different message codes' };
  }],

  ['S10b', 'Reused serial: a new alert carrying an old serial is never suppressed', async () => {
    const serial = randomSerial();
    const firstTag = label('s10b1');
    const secondTag = label('s10b2');
    inject({ text: arabic(firstTag), serial });
    await waitFor('first alert', eventWithText(firstTag), 10000);
    await dismissAlerts();
    inject({ text: arabic(secondTag), serial });
    const second = await waitFor('second alert', eventWithText(secondTag), 10000);
    const final = await waitFor('provider-backed record', async () => {
      const found = await eventWithText(secondTag)();
      return found.captureMethod === 'cell_broadcast_provider' ? found : null;
    }, 20000, 300);
    await dismissAlerts();
    expectEqual(final.cellBroadcast.serialNumber, serial, 'serialNumber');
    return { detail: `same serial 0x${serial.toString(16)}, two incidents, first seen via ${second.captureMethod}` };
  }],

  ['S11', 'Official exercise channel is flagged as a test', async () => {
    const tag = label('s11');
    inject({ id: 4381, text: arabic(tag) });
    const event = await waitFor('event', eventWithText(tag), 10000);
    await dismissAlerts();
    expectEqual(event.alertClass, 'exercise', 'alertClass');
    expectEqual(event.testOrExercise, true, 'testOrExercise');
    return { detail: `displayedBySystem=${event.cellBroadcast.displayedBySystem}` };
  }],

  ['S12', 'Method B alone: accessibility reads the real dialog; result stays unverified', async () => {
    shell(`pm revoke ${PKG} ${CELL_PERMISSION}`);
    await sleep(1000);
    launch();
    await waitForHealthyApp();
    await waitFor('accessibility reconnect', () => shell('dumpsys accessibility').includes('AlertAccessibilityService'), 15000, 500);
    await sleep(2000);
    shell('input keyevent KEYCODE_HOME');
    const tag = label('s12');
    s12Tag = tag;
    const text = arabic(tag);
    const { at } = inject({ text });
    const event = await waitFor('event', eventWithText(tag), 12000);
    expectEqual(event.message, text, 'message');
    expectEqual(event.captureMethod, 'accessibility', 'captureMethod');
    expectEqual(event.source, 'android_system_alert_ui', 'source');
    expectEqual(event.verified, false, 'verified');
    expectEqual(event.verification.status, 'system_component_ui', 'verification.status');
    expectEqual(event.alertClass, 'unknown', 'alertClass');
    expectEqual(event.severity, 'unknown', 'severity');
    expectEqual(event.originPackage, 'com.google.android.cellbroadcastreceiver', 'originPackage');
    expectTrue(event.title && event.title.length > 0, 'title should be read from the dialog');
    await dismissAlerts();
    return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: `title="${event.title}"` };
  }],

  ['S13', 'Upgrade: re-granting finds the same alert and upgrades the event in place', async () => {
    expectTrue(s12Tag, 'needs S12 to have run');
    const before = await eventWithText(s12Tag)();
    expectTrue(before, 'needs the event from S12');
    shell(`pm grant ${PKG} ${CELL_PERMISSION}`);
    const upgraded = await waitFor('upgrade', async () => {
      const found = (await events()).find((event) => event.eventId === before.eventId);
      return found && found.revision === 2 ? found : null;
    }, 30000, 500);
    expectEqual(upgraded.source, 'android_cell_broadcast', 'source');
    expectEqual(upgraded.captureMethod, 'cell_broadcast_provider', 'captureMethod');
    expectEqual(upgraded.alertClass, 'national_alert', 'alertClass');
    expectEqual(upgraded.title, before.title, 'title kept');
    expectEqual(upgraded.previousRevisions.join(), '1', 'previousRevisions');
    return { detail: 'same eventId, revision 2, server replaced the record' };
  }],

  ['S14', 'Method A alone: no notification while the dialog shows, captured after leaving it', async () => {
    shell(`pm revoke ${PKG} ${CELL_PERMISSION}`);
    shell('settings delete secure enabled_accessibility_services');
    await sleep(1000);
    launch();
    await waitForHealthyApp();
    await waitFor('listener', () => shell('cmd notification list_listeners 2>/dev/null; dumpsys notification').includes('AlertNotificationListenerService'), 10000, 500);
    await sleep(2000);
    shell('input keyevent KEYCODE_HOME');
    await sleep(1000);
    const tag = label('s14');
    const text = arabic(tag);
    inject({ text });
    await waitFor('dialog', () => alertWindowShowing(), 8000, 300);
    await sleep(5000);
    expectTrue(!(await eventWithText(tag)()), 'stock Android posts no notification while the dialog is showing');
    shell('input keyevent KEYCODE_HOME');
    const left = Date.now();
    const event = await waitFor('event after leaving dialog', eventWithText(tag), 10000);
    expectEqual(event.message, text, 'message');
    expectEqual(event.captureMethod, 'notification', 'captureMethod');
    expectEqual(event.source, 'android_system_notification', 'source');
    expectEqual(event.verified, false, 'verified');
    expectEqual(event.verification.status, 'system_component_ui', 'verification.status');
    shell(`pm grant ${PKG} ${CELL_PERMISSION}`);
    shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
    return { latencyMs: Date.parse(event.receivedByServerAt) - left, detail: 'silent for 5 s with the dialog up; latency measured from pressing Home' };
  }],

  ['S14b', 'Listener alone, code-like alerts: hidden or not, each alert is reported and no placeholder is sent', async () => {
    const sdk = Number(shell('getprop ro.build.version.sdk'));
    shell(`pm revoke ${PKG} ${CELL_PERMISSION}`);
    shell('settings delete secure enabled_accessibility_services');
    await sleep(1000);
    launch();
    await waitForHealthyApp();
    await waitFor('listener', () => shell('dumpsys notification').includes('AlertNotificationListenerService'), 10000, 500);
    await sleep(2000);
    shell('input keyevent KEYCODE_HOME');
    await sleep(1000);
    try {
      const before = new Set((await events()).map((event) => event.eventId));
      const seen = [];
      for (const tag of [label('s14b1'), label('s14b2')]) {
        const text = codeLike(tag);
        inject({ text });
        await waitFor('dialog', () => alertWindowShowing(), 8000, 300);
        await sleep(1000);
        shell('input keyevent KEYCODE_HOME');
        const event = await waitFor('new event', async () => (await events())
          .find((found) => !before.has(found.eventId) && !seen.some((other) => other.eventId === found.eventId)), 10000);
        seen.push(event);
        // Measured: the Android 15 image hides these and the Android 16 image does not, so past
        // 15 this only checks that the app follows whatever Android did.
        const hidden = alertNotification()?.hiddenFromListeners ?? false;
        if (sdk <= 35) expectEqual(hidden, sdk === 35, 'hidden on the tested Android 15 image only');
        expectEqual(event.textHidden, hidden, 'textHidden');
        expectEqual(event.message.includes('Sensitive notification content'), false, 'the placeholder must never be sent as text');
        expectEqual(event.message.includes(tag), !hidden, 'alert text arrives only when Android shows it');
        expectEqual(event.captureMethod, 'notification', 'captureMethod');
        expectEqual(event.verified, false, 'verified');
        await clearUnreadAlerts();
      }
      return { detail: `sdk ${sdk}: two alerts, two events, textHidden=${seen[0].textHidden}` };
    } finally {
      shell(`pm grant ${PKG} ${CELL_PERMISSION}`);
      shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
    }
  }],

  ['S14c', 'All detectors on: leaving the dialog of a code-like alert adds no second event', async () => {
    const before = new Set((await events()).map((event) => event.eventId));
    const tag = label('s14c');
    const text = codeLike(tag);
    inject({ text });
    await waitFor('event', eventWithText(tag), 10000);
    await waitFor('dialog', () => alertWindowShowing(), 8000, 300);
    clearLog();
    shell('input keyevent KEYCODE_HOME');
    await waitFor('notification seen', () => /decision=ACCEPT/.test(appLog()), 10000, 300);
    await sleep(3000);
    const fresh = (await events()).filter((event) => !before.has(event.eventId));
    expectEqual(fresh.length, 1, 'events on server');
    expectEqual(fresh[0].message, text, 'message');
    expectEqual(fresh[0].textHidden, false, 'textHidden');
    const hidden = appLog().match(/hidden=(true|false)/)?.[0];
    // The notification is still posted, so the restarted listener reads it again before any
    // detector has reported in. That must not become a new incident either.
    clearLog();
    shell(`run-as ${PKG} kill -9 ${shell(`pidof ${PKG}`)}`);
    await sleep(1500);
    await waitForHealthyApp();
    await waitFor('notification read again', () => /decision=ACCEPT/.test(appLog()), 30000, 500);
    await sleep(3000);
    expectEqual((await events()).filter((event) => !before.has(event.eventId)).length, 1, 'events on server after restart');
    return { detail: `one event with the full text, also after a restart; ${hidden} on the notification` };
  }],

  ['S15', 'Simulations stay on the test route and never count as verified', simulationScenario],

  ['S16', 'Reboot: receiver returns without opening the UI and catches an alert', async () => {
    adb('reboot');
    await sleep(5000);
    await waitForBoot();
    await waitForHealthyApp();
    const tag = label('s16');
    const { at } = inject({ text: arabic(tag) });
    const event = await waitFor('event', eventWithText(tag), 30000, 500);
    await dismissAlerts();
    return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: 'foreground service restored by BOOT_COMPLETED' };
  }],

  ['S16b', 'PIN-locked reboot: an alert is handled before anyone unlocks the phone', async () => {
    shell('locksettings set-pin 1234');
    try {
      adb('reboot');
      await sleep(5000);
      await waitForBoot();
      await sleep(4000);
      await waitFor('receiver running before unlock', () => tryShell(`dumpsys activity services ${PKG}`).includes('isForeground=true'), 40000, 1000);
      expectTrue(shell('getprop sys.user.0.ce_available') !== 'true', 'the phone must not be unlocked yet');
      const tag = label('s16b');
      const text = arabic(tag);
      const { at } = inject({ text });
      const event = await waitFor('event before unlock', eventWithText(tag), 20000, 200);
      expectEqual(event.message, text, 'message');
      expectTrue(shell('getprop sys.user.0.ce_available') !== 'true', 'the event must arrive before the first unlock');
      shell('input keyevent KEYCODE_WAKEUP');
      await dismissAlerts();
      const attempts = await firstUnlock('1234');
      return { latencyMs: Date.parse(event.receivedByServerAt) - at, detail: `delivered before the first unlock via ${event.captureMethod}; unlocked afterwards on attempt ${attempts}` };
    } finally {
      tryShell('locksettings clear --old 1234');
      wake();
    }
  }],

  ['S16c', 'Unattended dialog: re-reading it past the duplicate window sends nothing new', async () => {
    shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION --ei duplicate_window 1`);
    try {
      await sleep(500);
      const tag = label('s16c');
      inject({ text: arabic(tag) });
      await waitFor('first event', eventWithText(tag), 10000);
      await waitFor('dialog', () => alertWindowShowing(), 8000, 300);
      await sleep(70000);
      expectTrue(alertWindowShowing(), 'the dialog must still be up, as on an unattended phone');
      clearLog();
      // Reconnecting both services forces a fresh read of the same dialog.
      shell('settings delete secure enabled_accessibility_services');
      await sleep(1500);
      shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
      await sleep(4000);
      shell('input keyevent KEYCODE_HOME');
      await sleep(1500);
      shell(`cmd notification disallow_listener ${SERVICE_LISTENER}`);
      await sleep(1000);
      shell(`cmd notification allow_listener ${SERVICE_LISTENER}`);
      await sleep(4000);
      expectEqual((await events()).filter((event) => event.message.includes(tag)).length, 1, 'records on server');
      return { detail: 'dialog left up for 70 s past a 60 s window, then both services re-read it: still one event' };
    } finally {
      shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION --ei duplicate_window 30`);
    }
  }],

  ['S17', 'Heartbeat reports detector health to the server', async () => {
    const beat = await waitFor('heartbeat with an active provider', async () => {
      const current = await mock('GET', '/__mock/state');
      return current.heartbeats.find((entry) => entry.detectors.cell_broadcast_provider === 'ACTIVE');
    }, 60000, 1000);
    return { detail: `detectors=${JSON.stringify(beat.detectors)} pending=${beat.pendingEvents}` };
  }],
];

const hookCalls = async () => (await mock('GET', '/__mock/state')).hookCalls;
const hookPaths = async (from) => (await hookCalls()).slice(from).map((call) => call.path);

async function waitForPaCall(from, path, timeoutMs = 8000) {
  return waitFor(`call to ${path}`, async () => (await hookCalls()).slice(from).find((call) => call.path === path && call.status === 200), timeoutMs, 50);
}

/** Puts the target in the off state, so that scenarios do not depend on each other. */
async function hookOff() {
  const tag = label('off');
  inject({ id: 4377, text: allClear(tag) });
  await waitFor('all-clear event', eventWithText(tag), 10000);
  await sleep(1500);
  await dismissAlerts();
  await clearUnreadAlerts();
  shell('input keyevent KEYCODE_HOME');
  return (await hookCalls()).length;
}

const PA_SCENARIOS = [
  ['T01', 'Warning: the on URL is called at once', async () => {
    const from = await hookOff();
    const { at } = inject({ id: 4377, text: warning(label('t01')) });
    const call = await waitForPaCall(from, '/hook/on');
    await dismissAlerts();
    expectEqual(call.method, 'GET', 'method');
    expectEqual((await hookPaths(from)).join(), '/hook/on', 'calls');
    return { latencyMs: Date.parse(call.at) - at, detail: 'injection to the on URL' };
  }],

  ['T02', 'Repeated warning calls nothing more, and the all-clear calls the off URL', async () => {
    const from = await hookOff();
    inject({ id: 4377, text: warning(label('t02a')) });
    await waitForPaCall(from, '/hook/on');
    await dismissAlerts();
    const repeat = label('t02b');
    inject({ id: 4377, text: warning(repeat) });
    await waitFor('repeat event', eventWithText(repeat), 10000);
    await sleep(2000);
    await dismissAlerts();
    expectEqual((await hookPaths(from)).join(), '/hook/on', 'calls after the repeat');
    const { at } = inject({ id: 4377, text: allClear(label('t02c')) });
    const call = await waitForPaCall(from, '/hook/off');
    await dismissAlerts();
    expectEqual((await hookPaths(from)).join(), '/hook/on,/hook/off', 'calls');
    return { latencyMs: Date.parse(call.at) - at, detail: 'injection to the off URL' };
  }],

  ['T03', 'Warning, all-clear, identical warning: the on URL is called again', async () => {
    const from = await hookOff();
    const text = warning(label('t03'));
    const first = randomSerial();
    inject({ id: 4377, text, serial: first });
    await waitForPaCall(from, '/hook/on');
    await dismissAlerts();
    inject({ id: 4377, text: allClear(label('t03c')) });
    await waitForPaCall(from, '/hook/off');
    await dismissAlerts();
    inject({ id: 4377, text, serial: first ^ 0x10 });
    await waitFor('third call', async () => (await hookPaths(from)).length === 3, 8000, 100);
    await dismissAlerts();
    expectEqual((await hookPaths(from)).join(), '/hook/on,/hook/off,/hook/on', 'calls');
    return { detail: 'the 18 March 2026 pattern, inside the 30 minute window' };
  }],

  ['T04', 'An alert no rule matches calls nothing', async () => {
    const from = await hookOff();
    const tag = label('t04');
    inject({ id: 4396, language: 'en', text: `Roads to the Trade Centre are congested, please use other routes. Lab reference ${tag}` });
    const event = await waitFor('provider record', providerEventWithText(tag), 12000, 200);
    await sleep(2000);
    await dismissAlerts();
    expectEqual(event.alertClass, 'public_safety', 'alertClass');
    expectEqual((await hookPaths(from)).length, 0, 'calls');
    return { detail: 'recorded as an event, no URL called' };
  }],

  ['T05', 'The exercise channel calls nothing, even with the real words', async () => {
    const from = await hookOff();
    const tag = label('t05');
    inject({ id: 4381, text: warning(tag) });
    await waitFor('event', eventWithText(tag), 10000);
    await sleep(2000);
    await dismissAlerts();
    expectEqual((await hookPaths(from)).length, 0, 'calls');
    return { detail: 'channel 4381' };
  }],

  ['T06', 'Screen text alone calls nothing, and fires only once the provider confirms it', async () => {
    const from = await hookOff();
    shell(`pm revoke ${PKG} ${CELL_PERMISSION}`);
    await sleep(1000);
    launch();
    await waitForHealthyApp();
    await waitFor('accessibility reconnect', () => shell('dumpsys accessibility').includes('AlertAccessibilityService'), 15000, 500);
    await sleep(2000);
    shell('input keyevent KEYCODE_HOME');
    const tag = label('t06');
    inject({ id: 4377, text: warning(tag) });
    const event = await waitFor('event', eventWithText(tag), 12000);
    expectEqual(event.captureMethod, 'accessibility', 'captureMethod');
    await sleep(3000);
    await dismissAlerts();
    expectEqual((await hookPaths(from)).length, 0, 'calls from unverified text');
    shell(`pm grant ${PKG} ${CELL_PERMISSION}`);
    await waitForPaCall(from, '/hook/on', 30000);
    return { detail: 'silent on dialog text, fired when the provider row confirmed the same alert' };
  }],

  ['T07', 'The on URL fails three times: retried until it gets through, once', async () => {
    const from = await hookOff();
    await mock('POST', '/__mock/faults', { hookFailPath: '/hook/on', hookFailNext: 3 });
    const { at } = inject({ id: 4377, text: warning(label('t07')) });
    const call = await waitForPaCall(from, '/hook/on', 40000);
    await dismissAlerts();
    await sleep(3000);
    const calls = (await hookCalls()).slice(from);
    expectEqual(calls.filter((entry) => entry.status === 503).length, 3, 'failed calls');
    expectEqual(calls.filter((entry) => entry.status === 200).length, 1, 'successful calls');
    return { latencyMs: Date.parse(call.at) - at, detail: '3 injected 503 responses' };
  }],

  ['T08', 'A trigger stuck behind failures can never reach the target after the all-clear', async () => {
    const from = await hookOff();
    await mock('POST', '/__mock/faults', { hookFailPath: '/hook/on', hookFailNext: 1000 });
    inject({ id: 4377, text: warning(label('t08')) });
    await waitFor('a failed call', async () => (await hookCalls()).slice(from).some((call) => call.status === 503), 10000, 100);
    await dismissAlerts();
    inject({ id: 4377, text: allClear(label('t08c')) });
    await waitForPaCall(from, '/hook/off', 15000);
    await dismissAlerts();
    await mock('POST', '/__mock/faults', { hookFailPath: null, hookFailNext: 0 });
    await sleep(12000);
    const calls = (await hookCalls()).slice(from);
    expectEqual(calls.filter((call) => call.path === '/hook/on' && call.status === 200).length, 0, 'on calls that succeeded');
    expectEqual(calls[calls.length - 1].path, '/hook/off', 'last call');
    return { detail: `${calls.filter((call) => call.status === 503).length} failed on calls, then off, then silence for 12 s with the URL working again` };
  }],

  ['T09', 'Warning and all-clear both arrive while the app is down: the on URL is never called', async () => {
    const from = await hookOff();
    shell(`cmd notification disallow_listener ${SERVICE_LISTENER}`);
    shell('settings delete secure enabled_accessibility_services');
    await sleep(500);
    shell(`am force-stop ${PKG}`);
    await sleep(1500);
    inject({ id: 4377, text: warning(label('t09')) });
    await sleep(2000);
    await dismissAlerts();
    const tag = label('t09c');
    inject({ id: 4377, text: allClear(tag) });
    await sleep(2000);
    await dismissAlerts();
    launch();
    await waitFor('catch-up', eventWithText(tag), 25000, 200);
    await sleep(3000);
    shell(`cmd notification allow_listener ${SERVICE_LISTENER}`);
    shell(`settings put secure enabled_accessibility_services ${SERVICE_A11Y}`);
    shell('input keyevent KEYCODE_HOME');
    const paths = await hookPaths(from);
    expectTrue(!paths.includes('/hook/on'), `the on URL must not fire, got ${paths.join()}`);
    return { detail: `calls after catch-up: ${paths.join() || 'none, the target was already off'}` };
  }],

  ['T10', 'Not armed: a warning calls nothing', async () => {
    const from = await hookOff();
    shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION --ez armed false`);
    await sleep(500);
    try {
      const tag = label('t10');
      inject({ id: 4377, text: warning(tag) });
      await waitFor('event', eventWithText(tag), 10000);
      await sleep(2000);
      await dismissAlerts();
      expectEqual((await hookPaths(from)).length, 0, 'calls');
      return { detail: 'recorded as an event, no URL called' };
    } finally {
      shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION --ez armed true`);
    }
  }],

  ['T11', 'After a reboot the trigger still works without opening the app', async () => {
    const from = await hookOff();
    adb('reboot');
    await sleep(5000);
    await waitForBoot();
    await waitForHealthyApp();
    const { at } = inject({ id: 4377, text: warning(label('t11')) });
    const call = await waitForPaCall(from, '/hook/on', 30000);
    await dismissAlerts();
    return { latencyMs: Date.parse(call.at) - at, detail: 'armed state and URLs survived the reboot' };
  }],
];

async function simulationScenario() {
  clearLog();
  const before = new Set((await events()).map((event) => event.eventId));
  launch();
  await sleep(1500);
  simulate('a', 'ar');
  await sleep(2500);
  simulate('c', 'en');
  await sleep(1500);
  simulate('b', 'ar');
  await sleep(3000);
  shell('input keyevent KEYCODE_BACK');
  const fresh = await waitFor('three simulated events', async () => {
    const found = (await events()).filter((event) => !before.has(event.eventId));
    return found.length >= 3 ? found : null;
  }, 20000, 500);
  for (const event of fresh) {
    expectEqual(event.simulation, true, 'simulation');
    expectEqual(event.verified, false, 'verified');
    expectEqual(event.route, '/api/emergency/test-alerts', 'route');
    expectEqual(event.source, 'simulation', 'source');
    expectTrue(/SIMULATED|محاكاة/.test(event.message), 'message is labelled');
  }
  const methods = fresh.map((event) => event.captureMethod).sort().join();
  expectEqual(methods, 'accessibility,notification,simulation', 'capture methods');
  shell('input keyevent KEYCODE_HOME');
  return { detail: `Tests A, B and C all on the test route: ${methods}` };
}

const STOCK_SCENARIOS = [
  ['K01', 'Retail image: adb grant unlocks the history provider', async () => {
    expectEqual(shell('getprop ro.debuggable'), '0', 'ro.debuggable');
    const dump = shell(`dumpsys package ${PKG}`);
    expectTrue(/READ_CELL_BROADCASTS: granted=true/.test(dump), 'permission granted');
    expectTrue(!/pkgFlags=\[[^\]]*(SYSTEM|PRIVILEGED)/.test(dump), 'must be an ordinary third-party app');
    // Setting a PIN also unlocks, so the dashboard opens behind the app lock.
    shell(`am broadcast -n ${PKG}/.DebugProvisioningReceiver -a app.cellalerthook.debug.PROVISION --es test_pin 246813`);
    await sleep(1500);
    launch();
    await waitFor('provider active on the dashboard', () => {
      const ui = tryShell('uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml');
      return /text="Cell Broadcast provider"[\s\S]{0,600}?text="Active"/.test(ui);
    }, 20000, 1000);
    shell('input keyevent KEYCODE_HOME');
    const flags = dump.match(/READ_CELL_BROADCASTS: granted=true, flags=\[([^\]]*)\]/)[1].trim();
    return { detail: `third-party app, provider Active on dashboard; grant flags=${flags}; ${shell('getprop ro.build.fingerprint')}` };
  }],

  ['K02', 'Retail image: the framework injection hook does not exist', async () => {
    const tag = label('k02');
    inject({ text: arabic(tag) });
    await sleep(5000);
    expectTrue(!(await eventWithText(tag)()), 'no event may appear');
    expectTrue(!alertWindowShowing(), 'no alert window may appear');
    return { detail: 'injection broadcast had no effect, as AOSP requires ro.debuggable=1' };
  }],

  ['K03', 'Retail image: the signature permission cannot be granted', async () => {
    const output = tryShell(`pm grant ${PKG} android.permission.RECEIVE_EMERGENCY_BROADCAST 2>&1`);
    expectTrue(/not a changeable|has not requested|Exception|ERROR/.test(output), `expected refusal, got: ${output}`);
    return { detail: output.split('\n')[0].slice(0, 120) };
  }],

  ['K04', 'Retail image: simulations run and stay on the test route', simulationScenario],
];

async function main() {
  await fetch(`${MOCK}/api/emergency/health`, { headers: { Authorization: `Bearer ${TOKEN}` } })
    .catch(() => { throw new Error(`Mock server not reachable at ${MOCK}`); });

  const scenarios = (args.mode === 'stock' ? STOCK_SCENARIOS : [...FULL_SCENARIOS, ...PA_SCENARIOS])
    .filter(([id]) => !args.only || args.only.includes(id));

  if (args.skipSetup) {
    await provision();
  } else {
    await setup();
  }

  const results = [];
  for (const [id, title, run] of scenarios) {
    if (args.mode === 'full' && !CONTINUES_PREVIOUS.has(id)) await restoreBaseline();
    clearLog();
    const started = Date.now();
    process.stdout.write(`${id} ${title} ... `);
    try {
      const outcome = (await run()) ?? {};
      results.push({ id, title, passed: true, ...outcome, durationMs: Date.now() - started });
      console.log(`PASS${outcome.latencyMs !== undefined ? ` (${outcome.latencyMs} ms)` : ''}`);
    } catch (error) {
      results.push({ id, title, passed: false, error: error.message, durationMs: Date.now() - started });
      console.log(`FAIL\n    ${error.message}`);
      if (/FATAL EXCEPTION/.test(appLog())) console.log(appLog().slice(-1500));
      await dismissAlerts().catch(() => {});
    }
  }

  const passed = results.filter((result) => result.passed).length;
  console.log(`\n${passed}/${results.length} scenarios passed, ${injected} PDUs injected`);

  if (args.out) {
    fs.mkdirSync(path.dirname(args.out), { recursive: true });
    fs.writeFileSync(args.out, JSON.stringify({
      generatedAt: new Date().toISOString(),
      device: args.device,
      fingerprint: shell('getprop ro.build.fingerprint'),
      sdk: shell('getprop ro.build.version.sdk'),
      debuggable: shell('getprop ro.debuggable'),
      cellBroadcastModule: shell('pm list packages -f com.google.android.cellbroadcastservice'),
      overTheAir: false,
      results,
    }, null, 2));
    console.log(`Results written to ${args.out}`);
  }
  process.exit(passed === results.length ? 0 : 1);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(2);
});
