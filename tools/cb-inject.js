#!/usr/bin/env node
'use strict';
/**
 * Lab-only Cell Broadcast injector.
 *
 * Builds real 3GPP TS 23.041 GSM Cell Broadcast PDUs and hands them to the
 * telephony framework through the AOSP test hook
 * `com.android.internal.telephony.gsm.TEST_TRIGGER_CELL_BROADCAST`
 * (frameworks/opt/telephony GsmInboundSmsHandler).
 *
 * The hook is only registered when ro.debuggable=1, so this works on userdebug
 * emulator images and is rejected by design on retail phones. Nothing here
 * touches a radio: the PDU enters the phone process and travels the same path
 * an over-the-air page takes after the modem:
 *   GsmCellBroadcastHandler -> CellBroadcastProvider -> CellBroadcastReceiver.
 *
 * Usage:
 *   node tools/cb-inject.js --device emulator-5556 --id 4370 --lang ar --text "..."
 *   node tools/cb-inject.js --id 4383 --lang en --text "Test message"
 *   node tools/cb-inject.js --id 4370 --lang ar --text "..." --dry-run
 *
 * Exported functions are pure so they can be unit-tested with `node --test`.
 */

const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const os = require('node:os');
const path = require('node:path');

const PDU_LENGTH = 88;
const HEADER_LENGTH = 6;
const CONTENT_LENGTH = PDU_LENGTH - HEADER_LENGTH;

const TEST_ACTION = 'com.android.internal.telephony.gsm.TEST_TRIGGER_CELL_BROADCAST';

/** DCS 0x11: UCS-2 preceded by a two-character language indication (TS 23.038). */
const DCS_UCS2_WITH_LANGUAGE = 0x11;
/** DCS 0x01: GSM 7-bit default alphabet, English (TS 23.038). */
const DCS_GSM7_ENGLISH = 0x01;

const CR = 0x0d;

// GSM 03.38 default alphabet. Index is the septet value.
const GSM7_ALPHABET =
  '@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !"#¤%&\'()*+,-./0123456789:;<=>?' +
  '¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà';

function gsm7Septets(text) {
  const septets = [];
  for (const ch of text) {
    const value = GSM7_ALPHABET.indexOf(ch);
    if (value < 0) {
      throw new Error(`Character ${JSON.stringify(ch)} is not in the GSM 7-bit alphabet; use --lang ar for UCS-2`);
    }
    septets.push(value);
  }
  return septets;
}

function packSeptets(septets, outputLength) {
  const out = Buffer.alloc(outputLength, 0);
  let bitPosition = 0;
  for (const septet of septets) {
    const byteIndex = Math.floor(bitPosition / 8);
    const shift = bitPosition % 8;
    if (byteIndex >= outputLength) break;
    out[byteIndex] |= (septet << shift) & 0xff;
    if (shift > 1 && byteIndex + 1 < outputLength) {
      out[byteIndex + 1] |= septet >> (8 - shift);
    }
    bitPosition += 7;
  }
  return out;
}

function header({ serial, messageId, dcs, page, totalPages }) {
  const h = Buffer.alloc(HEADER_LENGTH);
  h.writeUInt16BE(serial & 0xffff, 0);
  h.writeUInt16BE(messageId & 0xffff, 2);
  h[4] = dcs;
  h[5] = ((page & 0x0f) << 4) | (totalPages & 0x0f);
  return h;
}

function splitUcs2(text, charsPerPage) {
  const units = [];
  for (let i = 0; i < text.length; i++) units.push(text.charCodeAt(i));
  const pages = [];
  for (let i = 0; i < units.length; i += charsPerPage) {
    let end = Math.min(i + charsPerPage, units.length);
    // Never split a surrogate pair across a page boundary.
    if (end < units.length && units[end - 1] >= 0xd800 && units[end - 1] <= 0xdbff) end -= 1;
    pages.push(units.slice(i, end));
    i = end - charsPerPage;
  }
  return pages.length ? pages : [[]];
}

/** Builds the UCS-2 pages. Language occupies two packed bytes of each page. */
function buildUcs2Pdus({ messageId, serial, language, text }) {
  const lang = packSeptets(gsm7Septets(language), 2);
  const charsPerPage = Math.floor((CONTENT_LENGTH - lang.length) / 2);
  const pages = splitUcs2(text, charsPerPage);
  if (pages.length > 15) throw new Error('Message exceeds the 15-page Cell Broadcast limit');
  return pages.map((units, index) => {
    const content = Buffer.alloc(CONTENT_LENGTH, 0);
    lang.copy(content, 0);
    let offset = lang.length;
    for (const unit of units) {
      content.writeUInt16BE(unit, offset);
      offset += 2;
    }
    // TS 23.041 pads the unused part of a page with CR.
    while (offset + 1 < CONTENT_LENGTH) {
      content.writeUInt16BE(CR, offset);
      offset += 2;
    }
    const pageHeader = header({
      serial,
      messageId,
      dcs: DCS_UCS2_WITH_LANGUAGE,
      page: index + 1,
      totalPages: pages.length,
    });
    return Buffer.concat([pageHeader, content]);
  });
}

function buildGsm7Pdus({ messageId, serial, text }) {
  const septets = gsm7Septets(text);
  const septetsPerPage = Math.floor((CONTENT_LENGTH * 8) / 7);
  const pages = [];
  for (let i = 0; i < septets.length; i += septetsPerPage) {
    pages.push(septets.slice(i, i + septetsPerPage));
  }
  if (pages.length === 0) pages.push([]);
  if (pages.length > 15) throw new Error('Message exceeds the 15-page Cell Broadcast limit');
  return pages.map((page, index) => {
    const padded = page.slice();
    while (padded.length < septetsPerPage) padded.push(CR);
    const pageHeader = header({
      serial,
      messageId,
      dcs: DCS_GSM7_ENGLISH,
      page: index + 1,
      totalPages: pages.length,
    });
    return Buffer.concat([pageHeader, packSeptets(padded, CONTENT_LENGTH)]);
  });
}

function buildPdus({ messageId, serial, language, text }) {
  const needsUcs2 = language !== 'en' || [...text].some((c) => !GSM7_ALPHABET.includes(c));
  return needsUcs2
    ? buildUcs2Pdus({ messageId, serial, language, text })
    : buildGsm7Pdus({ messageId, serial, text });
}

/** Geographic scope 3 (cell-wide, normal) with a random message code so each run is a new alert. */
function randomSerial() {
  const geographicalScope = 3;
  const messageCode = crypto.randomInt(0, 1024);
  const updateNumber = 0;
  return (geographicalScope << 14) | (messageCode << 4) | updateNumber;
}

function parseArgs(argv) {
  const options = { device: null, id: 4370, lang: 'ar', text: null, dryRun: false, serial: null, phoneId: 0 };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const next = () => {
      i += 1;
      if (i >= argv.length) throw new Error(`Missing value for ${arg}`);
      return argv[i];
    };
    switch (arg) {
      case '--device': options.device = next(); break;
      case '--id': options.id = Number.parseInt(next(), 0); break;
      case '--lang': options.lang = next(); break;
      case '--text': options.text = next(); break;
      case '--serial': options.serial = Number.parseInt(next(), 0); break;
      case '--phone-id': options.phoneId = Number.parseInt(next(), 10); break;
      case '--dry-run': options.dryRun = true; break;
      default: throw new Error(`Unknown argument ${arg}`);
    }
  }
  return options;
}

function adbPath() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
    (process.platform === 'win32' ? path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk') : path.join(os.homedir(), 'Android', 'Sdk'));
  return path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
}

function adb(device, args) {
  const prefix = device ? ['-s', device] : [];
  return execFileSync(adbPath(), [...prefix, ...args], { encoding: 'utf8' });
}

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (!options.text) throw new Error('--text is required');
  const serial = options.serial ?? randomSerial();
  const pdus = buildPdus({ messageId: options.id, serial, language: options.lang, text: options.text });
  console.log(`messageId=${options.id} serial=0x${serial.toString(16)} language=${options.lang} pages=${pdus.length}`);
  if (options.dryRun) {
    pdus.forEach((pdu, i) => console.log(`page ${i + 1}: ${pdu.toString('hex').toUpperCase()}`));
    return;
  }
  const debuggable = adb(options.device, ['shell', 'getprop', 'ro.debuggable']).trim();
  if (debuggable !== '1') {
    throw new Error('ro.debuggable is not 1: the framework test hook does not exist on this build');
  }
  for (const pdu of pdus) {
    const out = adb(options.device, [
      'shell', 'am', 'broadcast', '-a', TEST_ACTION,
      '--es', 'pdu_string', pdu.toString('hex').toUpperCase(),
      '--ei', 'phone_id', String(options.phoneId),
    ]);
    if (!/Broadcast completed/.test(out)) throw new Error(`Unexpected am output: ${out}`);
  }
  console.log('injected');
}

if (require.main === module) {
  try {
    main();
  } catch (error) {
    console.error(`cb-inject: ${error.message}`);
    process.exit(1);
  }
}

module.exports = { buildPdus, packSeptets, gsm7Septets, parseArgs, randomSerial, PDU_LENGTH };
