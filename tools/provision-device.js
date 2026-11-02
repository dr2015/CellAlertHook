#!/usr/bin/env node
'use strict';
/**
 * One-time setup of a dedicated receiver phone over USB debugging. No root is involved.
 *
 *   node tools/provision-device.js --device <serial> --apk app-debug.apk
 *
 * Everything goes through adb on purpose. Android 15's Enhanced Confirmation gates the
 * Settings screens, so a toggle there can show "Restricted setting" for a sideloaded app,
 * while `adb install` is a trusted install source and the grant path does not consult it.
 * See docs/SETUP.md.
 */

const { execFileSync } = require('node:child_process');
const os = require('node:os');
const path = require('node:path');

const PKG = 'app.cellalerthook';
const A11Y_SERVICE = `${PKG}/${PKG}.detection.AlertAccessibilityService`;
const LISTENER_SERVICE = `${PKG}/${PKG}.detection.AlertNotificationListenerService`;
const CELL_PERMISSION = 'android.permission.READ_CELL_BROADCASTS';

function parseArgs(argv) {
  const options = { device: null, apk: null };
  for (let i = 0; i < argv.length; i += 2) {
    const [flag, value] = [argv[i], argv[i + 1]];
    if (flag === '--device') options.device = value;
    else if (flag === '--apk') options.apk = value;
    else throw new Error(`Unknown argument ${flag}`);
  }
  if (!options.apk) throw new Error('--apk is required');
  return options;
}

function adbBinary() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
    (process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk') : path.join(os.homedir(), 'Android', 'Sdk'));
  return path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
}

/**
 * The accessibility list is shared with every other service on the phone, so it is
 * extended rather than replaced. Pure, so it can be tested without a device.
 */
function withService(current, service) {
  const existing = !current || current === 'null' ? [] : current.split(':').filter(Boolean);
  return existing.includes(service) ? existing.join(':') : [...existing, service].join(':');
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  const adb = (...args) => execFileSync(adbBinary(), [...(options.device ? ['-s', options.device] : []), ...args], { encoding: 'utf8' }).replace(/\r/g, '').trim();
  const shell = (command) => adb('shell', command);
  const attempt = (name, command) => {
    try {
      shell(command);
      console.log(`  ok    ${name}`);
      return true;
    } catch (error) {
      console.log(`  FAIL  ${name}: ${(error.stderr || error.message).toString().trim().split('\n')[0]}`);
      return false;
    }
  };

  const sdkLevel = Number(shell('getprop ro.build.version.sdk'));
  console.log(`Device: ${shell('getprop ro.product.manufacturer')} ${shell('getprop ro.product.model')}, Android ${shell('getprop ro.build.version.release')} (API ${sdkLevel})`);
  console.log(`Build:  ${shell('getprop ro.build.fingerprint')}`);
  if (sdkLevel < 30) throw new Error('Android 11 (API 30) or newer is required');

  console.log('\nInstalling');
  // A full copy, because this app must start before the first unlock and a streamed install
  // adds a dependency that nothing here needs.
  console.log(`  ${adb('install', '--no-incremental', '-r', options.apk).split('\n').pop()}`);

  console.log('\nGranting');
  const criticalOk = attempt('Cell Broadcast read access', `pm grant ${PKG} ${CELL_PERMISSION}`);
  if (sdkLevel >= 33) attempt('notifications', `pm grant ${PKG} android.permission.POST_NOTIFICATIONS`);
  attempt('notification access', `cmd notification allow_listener ${LISTENER_SERVICE}`);
  const accessibility = withService(shell('settings get secure enabled_accessibility_services'), A11Y_SERVICE);
  attempt('accessibility service', `settings put secure enabled_accessibility_services ${accessibility}`);
  attempt('accessibility enabled', 'settings put secure accessibility_enabled 1');

  console.log('\nHardening');
  attempt('battery optimization exemption', `dumpsys deviceidle whitelist +${PKG}`);
  attempt('keep permissions if unused', `cmd appops set ${PKG} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore`);
  attempt('allow background running', `cmd appops set ${PKG} RUN_ANY_IN_BACKGROUND allow`);

  console.log('\nStarting');
  attempt('launch', `am start -n ${PKG}/.ui.MainActivity`);

  // Measured on a Samsung: the first write was accepted, then the entry vanished within seconds.
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 5000);
  const listed = shell('settings get secure enabled_accessibility_services');
  if (!listed.includes(PKG)) {
    attempt('accessibility service, second try', `settings put secure enabled_accessibility_services ${withService(listed, A11Y_SERVICE)}`);
  }

  console.log('\nVerifying');
  const dump = shell(`dumpsys package ${PKG}`);
  const granted = new RegExp(`${CELL_PERMISSION}: granted=true`).test(dump);
  const provider = shell('cmd package resolve-content-provider cellbroadcasts 2>/dev/null || pm path com.google.android.cellbroadcastservice 2>/dev/null || true');
  console.log(`  READ_CELL_BROADCASTS granted: ${granted}`);
  console.log(`  Cell Broadcast module:        ${provider ? 'present' : 'not found under the AOSP name, check Diagnostics in the app'}`);
  console.log(`  accessibility list:           ${shell('settings get secure enabled_accessibility_services')}`);

  if (!criticalOk || !granted) {
    console.error('\nCell Broadcast read access was not granted. See the OEM notes in docs/SETUP.md.');
    process.exit(1);
  }
  console.log('\nDone. On the phone: create the PIN, turn on Protect from closing, paste the links, then press Check connection. See docs/URL_TRIGGER.md.');
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`provision-device: ${error.message}`);
    process.exit(1);
  }
}

module.exports = { withService };
