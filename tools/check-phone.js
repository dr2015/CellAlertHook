#!/usr/bin/env node
'use strict';
/**
 * Answers one question on a real phone: can an ordinary app read the emergency alerts stored on it?
 *
 *   node tools/check-phone.js [--device <serial>]
 *
 * Installs only the small history viewer in poc/, grants it the one Cell Broadcast permission
 * and prints what it reads. Nothing is sent anywhere, and the alerts are not changed.
 * Remove it afterwards with: adb uninstall app.cellalerthook.viewer
 */

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PKG = 'app.cellalerthook.viewer';
const PERMISSION = 'android.permission.READ_CELL_BROADCASTS';
const apkFlag = process.argv.indexOf('--apk');
const APK = apkFlag > 0 ? process.argv[apkFlag + 1] : path.join(__dirname, '..', 'poc', 'cb-provider-poc', 'build', 'cb-provider-poc.apk');
// Kept out of git, because the log names the phone and holds its alerts.
const REPORTS = path.join(__dirname, '..', 'reports');

function adbBinary() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
    (process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk') : path.join(os.homedir(), 'Android', 'Sdk'));
  return path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
}

const run = (...args) => execFileSync(adbBinary(), args, { encoding: 'utf8', timeout: 60000, maxBuffer: 1 << 26 }).replace(/\r/g, '');

/** Emulators are never the phone being asked about, so they are left out. Pure, so it can be tested. */
function physicalDevices(listing) {
  return listing.split('\n').slice(1)
    .map((line) => line.trim().split(/\s+/))
    .filter(([serial, state]) => serial && !serial.startsWith('emulator-') && state)
    .map(([serial, state]) => ({ serial, state }));
}

function chooseDevice(listing, requested) {
  if (requested) return requested;
  const phones = physicalDevices(listing);
  if (phones.length === 0) {
    throw new Error('No phone found. Turn on USB debugging, connect the cable, and accept the prompt on the phone.');
  }
  if (phones.length > 1) throw new Error(`More than one phone: ${phones.map((phone) => phone.serial).join(', ')}. Use --device.`);
  const [phone] = phones;
  if (phone.state === 'unauthorized') throw new Error('The phone has not accepted this computer. Look for the prompt on its screen and tap Allow.');
  if (phone.state !== 'device') throw new Error(`The phone is ${phone.state}. Reconnect the cable.`);
  return phone.serial;
}

/** Turns the viewer's log into the facts that decide the result. Pure, so it can be tested. */
function readResult(log) {
  const lines = log.split('\n').map((line) => line.replace(/^.*CellAlertHookPoc\s*:\s?/, ''));
  const count = lines.map((line) => line.match(/^ALERTS IN HISTORY: (\d+)/)?.[1]).find(Boolean);
  return {
    providerFound: lines.some((line) => line.startsWith('provider cellbroadcasts pkg=')),
    readable: lines.some((line) => /^query HISTORY OK/.test(line)),
    denied: lines.find((line) => /^query HISTORY DENIED/.test(line)) ?? null,
    alertCount: count === undefined ? null : Number(count),
  };
}

function verdict(result, retail) {
  if (!result.providerFound) {
    return 'NOT POSSIBLE THIS WAY: this phone does not have the standard Android alert history. Only on-screen capture could work here.';
  }
  if (!result.readable) return `BLOCKED: this phone refused access. ${result.denied ?? ''}`;
  if (result.alertCount > 0) {
    // Only a retail build rules out the lab test hook, so only there can these be called real.
    return `YES: an ordinary app with no root read ${result.alertCount} alert(s) stored on this phone. ` +
      (retail ? 'This is a retail build with no test hook, so they came from the network.' : 'This build is debuggable, so they may be lab injections.');
  }
  return 'ACCESS WORKS, NOTHING STORED: the app may read the history, but it is empty. That is not a failure: ' +
    'your phone\'s own alert list can be kept in a separate store. The next real alert will decide it.';
}

function main() {
  const flag = process.argv.indexOf('--device');
  const device = chooseDevice(run('devices'), flag > 0 ? process.argv[flag + 1] : null);
  const adb = (...args) => run('-s', device, ...args);
  const prop = (name) => adb('shell', 'getprop', name).trim();

  console.log(`Phone: ${prop('ro.product.manufacturer')} ${prop('ro.product.model')}, Android ${prop('ro.build.version.release')}`);
  adb('install', '-r', APK);
  try {
    adb('shell', 'pm', 'grant', PKG, PERMISSION);
  } catch (error) {
    console.log(`Grant refused: ${(error.stderr || error.message).toString().trim().split('\n')[0]}`);
  }
  adb('logcat', '-c');
  adb('shell', 'am', 'force-stop', PKG);
  adb('shell', 'am', 'start', '-n', `${PKG}/.PocActivity`);
  execFileSync(process.execPath, ['-e', 'setTimeout(() => {}, 3000)']);

  const log = adb('logcat', '-d', '-s', 'CellAlertHookPoc:I');
  const result = readResult(log);
  const retail = prop('ro.debuggable') === '0';
  const alerts = log.split('\n').map((line) => line.replace(/^.*CellAlertHookPoc\s*:\s?/, ''))
    .filter((line) => /^ALERT /.test(line) || (line && !/^[-a-z ]*(device sdk|permission |provider |emergency receiver|query |ALERTS IN|content observer|dynamic receiver)/.test(line) && !line.startsWith('---')));

  console.log(`\n${verdict(result, retail)}\n`);
  console.log(alerts.join('\n'));
  fs.mkdirSync(REPORTS, { recursive: true });
  const file = path.join(REPORTS, `phone-check-${prop('ro.product.model').replace(/\W+/g, '-')}.txt`);
  fs.writeFileSync(file, `${prop('ro.build.fingerprint')}\n${new Date().toISOString()}\n${verdict(result, retail)}\n\n${log}`);
  console.log(`\nSaved to ${file}\nThe list is also on the phone's screen. Remove the viewer with: adb uninstall ${PKG}`);
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

module.exports = { physicalDevices, chooseDevice, readResult, verdict };
