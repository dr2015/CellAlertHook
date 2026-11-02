#!/usr/bin/env node
'use strict';
/**
 * Drives the app lock on a lab device through the real screens.
 *
 *   node tools/lab-lock.js --device emulator-5556 --apk app-debug.apk --hook-host 10.0.2.2:8787
 *
 * Only for a lab device with the test receiver: it creates a test PIN, saves test links and
 * uninstalls the app. It never runs against a phone holding real links, because it would wipe them.
 */

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');

const PKG = 'app.cellalerthook';
const ADMIN = `${PKG}/.service.CloseProtectionReceiver`;

function options() {
  const argv = process.argv.slice(2);
  const parsed = { device: null, apk: null, hookHost: '10.0.2.2:8787', out: null, presetPin: null };
  for (let i = 0; i < argv.length; i += 2) {
    const [flag, value] = [argv[i], argv[i + 1]];
    if (flag === '--device') parsed.device = value;
    else if (flag === '--apk') parsed.apk = value;
    else if (flag === '--hook-host') parsed.hookHost = value;
    else if (flag === '--out') parsed.out = value;
    else if (flag === '--preset-pin') parsed.presetPin = value;
    else throw new Error(`Unknown argument ${flag}`);
  }
  if (!parsed.device || !parsed.apk) throw new Error('--device and --apk are required');
  if (!parsed.device.startsWith('emulator-')) throw new Error('This test wipes the app, so it runs on an emulator only');
  return parsed;
}

const opts = options();
const TEST_PIN = opts.presetPin || '246813';
const sdk = process.env.ANDROID_HOME || path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk');
const adbPath = path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
const adb = (...args) => execFileSync(adbPath, ['-s', opts.device, ...args], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).replace(/\r/g, '').trim();
const shell = (command) => adb('shell', command);
const tryShell = (command) => { try { return shell(command); } catch (error) { return `${error.stdout || ''}${error.stderr || ''}`.trim(); } };
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const results = [];
function record(id, name, passed, detail = '') {
  results.push({ id, name, passed, detail });
  console.log(`${passed ? 'PASS' : 'FAIL'} ${id} ${name}${detail ? ` | ${detail}` : ''}`);
}

function dump() {
  return tryShell('uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml');
}

function nodes(xml) {
  return [...xml.matchAll(/<node [^>]*>/g)].map(([tag]) => {
    const value = (name) => (tag.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1] ?? '').replace(/&amp;/g, '&');
    const [x1, y1, x2, y2] = (value('bounds').match(/\d+/g) || [0, 0, 0, 0]).map(Number);
    return { text: value('text'), cls: value('class'), enabled: value('enabled') === 'true', clickable: value('clickable') === 'true', x: (x1 + x2) >> 1, y: (y1 + y2) >> 1, x1, y1, x2, y2 };
  });
}

/** App info keeps the label enabled, so the state is on the smallest clickable area around it. */
function buttonEnabled(list, text) {
  const label = list.find((n) => n.text === text);
  if (!label) return null;
  const area = (n) => (n.x2 - n.x1) * (n.y2 - n.y1);
  const owner = list
    .filter((n) => n.clickable && n.x1 <= label.x && n.x2 >= label.x && n.y1 <= label.y && n.y2 >= label.y)
    .sort((a, b) => area(a) - area(b))[0];
  return (owner || label).enabled;
}

async function waitFor(pattern, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const xml = dump();
    if (pattern.test(xml)) return xml;
    await sleep(500);
  }
  return null;
}

const tap = (x, y) => shell(`input tap ${x} ${y}`);

async function tapText(text) {
  const node = nodes(dump()).find((n) => n.text === text);
  if (!node) throw new Error(`No "${text}" on screen`);
  tap(node.x, node.y);
  await sleep(700);
}

async function fill(index, value) {
  const field = nodes(dump()).filter((n) => n.cls === 'android.widget.EditText')[index];
  if (!field) throw new Error(`No text field ${index}`);
  tap(field.x, field.y);
  await sleep(500);
  shell(`input text '${value}'`);
  await sleep(500);
}

async function scrollTo(text) {
  const [width, height] = shell('wm size').match(/(\d+)x(\d+)/).slice(1).map(Number);
  for (let i = 0; i < 25; i++) {
    const found = nodes(dump()).find((n) => n.text === text);
    if (found && found.y < height * 0.88) return found;
    shell(`input swipe ${width >> 1} ${Math.round(height * 0.7)} ${width >> 1} ${Math.round(height * 0.3)} 300`);
    await sleep(500);
  }
  throw new Error(`Could not scroll to "${text}"`);
}

function open() {
  shell(`am start -W -n ${PKG}/.ui.MainActivity`);
}

async function background() {
  shell('input keyevent KEYCODE_HOME');
  await sleep(1200);
}

async function unlock(pin = TEST_PIN) {
  await fill(0, pin);
  await tapText('Unlock');
  await sleep(1500);
}

function receiverLog() {
  return new Promise((resolve, reject) => {
    http.get(`http://localhost:${opts.hookHost.split(':')[1]}/hook-log`, (response) => {
      let body = '';
      response.on('data', (chunk) => { body += chunk; });
      response.on('end', () => resolve(JSON.parse(body).calls));
    }).on('error', reject);
  });
}

const receiverCalls = async () => (await receiverLog()).length;

/** A preset build has no PIN to create: its first open takes the PIN sealed with the links. */
async function openSchoolBuild() {
  const xml = await waitFor(/finish setting up/);
  record('P01', 'First open asks for the setup PIN and shows nothing else', Boolean(xml) && !/text="Dashboard"/.test(xml));
  await unlock('11111111');
  record('P02', 'A wrong PIN does not open the links', Boolean(await waitFor(/Wrong PIN/, 8000)));
  await unlock();
  record('P03', 'The setup PIN opens the app', Boolean(await waitFor(/text="Dashboard"/, 20000)));
}

async function createPin() {
  let xml = await waitFor(/Create a PIN/);
  record('L01', 'First open asks for a new PIN and shows nothing else', Boolean(xml) && !/text="Dashboard"/.test(xml));

  await fill(0, TEST_PIN);
  await fill(1, '111111');
  await tapText('Save PIN');
  record('L02', 'Two different PINs are refused', Boolean(await waitFor(/do not match/, 4000)));

  const repeat = nodes(dump()).filter((n) => n.cls === 'android.widget.EditText')[1];
  tap(repeat.x, repeat.y);
  shell(`input keyevent KEYCODE_MOVE_END ${'KEYCODE_DEL '.repeat(8)}`);
  shell(`input text ${TEST_PIN}`);
  await sleep(500);
  await tapText('Save PIN');
  xml = await waitFor(/text="Dashboard"/);
  record('L03', 'A matching PIN opens the app', Boolean(xml));
}

/** adb cannot remove an active admin from this app, so a lab device is cleaned through Settings. */
async function removeProtectedApp() {
  shell('input keyevent KEYCODE_HOME');
  await sleep(800);
  // Settings would otherwise reopen on whichever page it was left on.
  shell('am force-stop com.android.settings');
  shell(`am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${PKG}`);
  await waitFor(/Force stop/);
  await tapText('Uninstall');
  await waitFor(/Deactivate/);
  await tapText('Deactivate & uninstall');
  await waitFor(/uninstall this app/);
  await tapText('OK');
  await sleep(2500);
}

async function main() {
  shell('input keyevent KEYCODE_WAKEUP');
  if (shell('dumpsys device_policy').includes('CloseProtectionReceiver')) await removeProtectedApp();
  tryShell(`pm uninstall ${PKG}`);
  adb('install', '--no-incremental', opts.apk);
  shell(`pm grant ${PKG} android.permission.READ_CELL_BROADCASTS`);
  shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`);
  const debuggable = /DEBUGGABLE/.test(shell(`dumpsys package ${PKG}`));
  shell('input keyevent KEYCODE_WAKEUP');
  tryShell('wm dismiss-keyguard');
  open();

  if (opts.presetPin) await openSchoolBuild();
  else await createPin();
  let xml;

  const windows = shell('dumpsys window windows');
  const appWindow = windows.split(/Window #\d+/).find((block) => block.includes(`${PKG}/${PKG}.ui.MainActivity`)) || '';
  record('L04', 'Screenshots and the recent apps preview are blocked', /fl=[^\n]*SECURE|SECURE/.test(appWindow.match(/fl=[^\n]*/)?.[0] || ''));

  await background();
  open();
  xml = await waitFor(/text="Unlock"/);
  record('L05', 'Leaving the app locks it', Boolean(xml) && !/text="Dashboard"/.test(xml));

  await unlock('999999');
  xml = await waitFor(/Wrong PIN/, 5000);
  record('L06', 'A wrong PIN stays locked', Boolean(xml) && !/text="Dashboard"/.test(xml));

  for (let i = 0; i < 4; i++) await unlock(`99999${i}`);
  xml = await waitFor(/Too many tries/, 5000);
  record('L07', 'Five wrong tries start a wait', Boolean(xml));

  shell('am force-stop ' + PKG);
  open();
  xml = await waitFor(/Too many tries/, 8000);
  record('L08', 'Restarting the app does not clear the wait', Boolean(xml));

  await sleep(31000);
  await waitFor(/text="Unlock"[^>]*enabled="true"/, 8000);
  await unlock();
  xml = await waitFor(/text="Dashboard"/);
  record('L09', 'The right PIN works after the wait', Boolean(xml));

  if (!opts.presetPin) {
    await tapText('Settings');
    await waitFor(/URL trigger/);
    await fill(0, `http://${opts.hookHost}/hook/on`);
    await fill(1, `http://${opts.hookHost}/hook/off`);
    const typed = dump();
    record('L10', 'A link is hidden while it is typed', !typed.includes('/hook/on') && !typed.includes('/hook/off'));
    // With no keyboard on screen, Back would leave the app and lock it.
    if (/mInputShown=true/.test(shell('dumpsys input_method'))) shell('input keyevent KEYCODE_BACK');
    await sleep(600);
    const save = await scrollTo('Save');
    tap(save.x, save.y);
    await sleep(1200);
    record('L11', 'Links save', /Settings saved/.test(dump()));
  }

  await background();
  open();
  await waitFor(/text="Unlock"/);
  await unlock();
  await waitFor(/text="Dashboard"/);
  await tapText('Settings');
  xml = await waitFor(/Saved and hidden/);
  const fields = xml ? nodes(xml).filter((n) => n.cls === 'android.widget.EditText') : [];
  record('L12', 'After saving, the app never shows a link again', Boolean(xml) && !xml.includes(opts.hookHost) && !/\/hook\/on/.test(xml), `first fields: ${fields.slice(0, 2).map((n) => JSON.stringify(n.text)).join(', ')}`);

  if (debuggable) {
    const stored = tryShell(`run-as ${PKG} cat /data/user_de/0/${PKG}/shared_prefs/receiver_credentials.xml`);
    record('L13', 'Links are encrypted on disk', stored.includes('activate_url') && !stored.includes('/hook/on') && !stored.includes(opts.hookHost));
  }

  const before = await receiverCalls();
  await tapText('Check connection');
  xml = await waitFor(/No request was sent/, 15000);
  await sleep(1500);
  const after = await receiverCalls();
  record('L14', 'Check connection reports the server and sends no request', Boolean(xml) && after === before, `calls before ${before}, after ${after}`);

  await tapText('Dashboard');
  await waitFor(/text="Dashboard"/);
  if (opts.presetPin) {
    const turnOn = await scrollTo('Call on URL');
    tap(turnOn.x, turnOn.y);
    await sleep(800);
    await tapText('Confirm');
    await sleep(3000);
    const last = (await receiverLog()).pop();
    record('P04', 'The link sealed in the APK really reaches the test receiver', last?.path === '/hook/on', `${last?.method} ${last?.path} ${last?.status}`);
    await tapText('Alerts');
    await tapText('Dashboard');
    await sleep(800);
  }
  const row = await scrollTo('Protect from closing');
  const allow = nodes(dump()).find((n) => n.text === 'Allow' && n.y >= row.y1 - 60 && n.y <= row.y2 + 90);
  tap(allow.x, allow.y);
  xml = await waitFor(/Activate this device admin app/);
  const noPowers = xml ? !/Erase all data|Change the screen lock|Lock the screen/.test(xml) : false;
  record('L15', 'Android shows its own consent screen, asking for no powers over the phone', Boolean(xml) && noPowers);
  await tapText('Activate this device admin app');
  await sleep(1500);
  record('L16', 'Consent activates the protection', shell('dumpsys device_policy').includes(ADMIN.split('/')[1].slice(1)) || shell('dumpsys device_policy').includes('CloseProtectionReceiver'));

  const refused = tryShell(`pm uninstall ${PKG}`);
  record('L17', 'Uninstall is refused while protected', /DELETE_FAILED_DEVICE_POLICY_MANAGER/.test(refused), refused.split('\n')[0]);

  const appInfo = async () => {
    shell('am force-stop com.android.settings');
    shell(`am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${PKG}`);
    return waitFor(/Force stop/);
  };
  xml = await appInfo();
  record('L18', 'Force stop is greyed out', Boolean(xml) && buttonEnabled(nodes(xml), 'Force stop') === false);
  await tapText('Uninstall');
  xml = await waitFor(/Deactivate/);
  record('L19', 'Uninstall stops at Android\'s own page, which must remove the protection first', Boolean(xml));
  await tapText('Cancel');
  await sleep(800);

  xml = await appInfo();
  await tapText('Storage & cache');
  xml = await waitFor(/Clear storage/);
  record('L20', 'Clear storage is greyed out', Boolean(xml) && buttonEnabled(nodes(xml), 'Clear storage') === false);
  shell('input keyevent KEYCODE_HOME');
  await sleep(800);

  shell(`cmd statusbar expand-settings`);
  await sleep(2500);
  const shade = nodes(dump()).find((n) => /active/i.test(n.text) && /app/i.test(n.text));
  if (shade) {
    tap(shade.x, shade.y);
    await sleep(2500);
    const list = dump();
    const shown = /CellAlertHook/.test(list);
    const items = nodes(list);
    const name = items.find((n) => /CellAlertHook/.test(n.text));
    const stop = name && items.find((n) => n.text === 'Stop' && Math.abs(n.y - name.y) < 120);
    record('L21', 'The notification shade has no Stop button for the app', !stop, shown ? 'listed without Stop' : 'not listed at all');
    shell('input keyevent KEYCODE_BACK');
    await sleep(500);
  } else {
    record('L21', 'The notification shade has no Stop button for the app', true, 'no active apps entry shown');
  }
  tryShell('cmd statusbar collapse');
  shell('input keyevent KEYCODE_HOME');
  await sleep(800);

  const running = shell(`dumpsys activity services ${PKG}`).includes('ReceiverForegroundService');
  record('L22', 'The receiver is still running behind the lock', running);

  if (!debuggable) {
    record('L23', 'Nobody can read the app\'s files over USB', /not debuggable/.test(tryShell(`run-as ${PKG} id`)));
    record('L24', 'The release has no test commands', !shell(`dumpsys package ${PKG}`).includes('DebugProvisioningReceiver'));
  }

  await removeProtectedApp();
  record('L25', 'Someone past the phone\'s own lock can still remove it, through Android\'s pages', !shell('pm list packages').includes(PKG));

  const passed = results.filter((r) => r.passed).length;
  console.log(`\n${passed} of ${results.length} passed`);
  if (opts.out) {
    fs.writeFileSync(opts.out, `${JSON.stringify({
      build: path.basename(opts.apk),
      device: `${shell('getprop ro.product.model')} API ${shell('getprop ro.build.version.sdk')}`,
      results,
    }, null, 2)}${os.EOL}`);
  }
  process.exit(passed === results.length ? 0 : 1);
}

main().catch((error) => {
  console.error(`lab-lock: ${error.message}`);
  process.exit(1);
});
