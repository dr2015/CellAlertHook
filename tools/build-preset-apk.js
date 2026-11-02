#!/usr/bin/env node
'use strict';
/**
 * Builds a preset APK, with your links sealed inside.
 *
 *   node tools/build-preset-apk.js
 *
 * It asks for the two links, a setup PIN and an optional server certificate, and hides what is
 * typed or pasted. The links are encrypted
 * with the PIN, so the APK can be copied to other phones and still nobody can read them from the
 * file. The links and the PIN are never written to disk, the screen or a log. Only the ciphertext
 * is, and the PIN opens it on each phone's first open.
 *
 * Sends nothing to the links. Add --out <file> to choose where the APK goes.
 */

const { spawnSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const ASSET = 'sealed_links.json';
const ITERATIONS = 600_000;

/** Must stay matched with PresetSeal.kt. Pure, so it can be tested without a build. */
function seal(activateUrl, clearUrl, pin, iterations = ITERATIONS, certificate = '') {
  const salt = crypto.randomBytes(16);
  const iv = crypto.randomBytes(12);
  const key = crypto.pbkdf2Sync(pin, salt, iterations, 32, 'sha256');
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const body = Buffer.concat([cipher.update(JSON.stringify({ activateUrl, clearUrl, certificate }), 'utf8'), cipher.final(), cipher.getAuthTag()]);
  return { iterations, salt: salt.toString('base64'), iv: iv.toString('base64'), data: body.toString('base64') };
}

function checkLink(link) {
  let url;
  try {
    url = new URL(link);
  } catch {
    return 'That is not a link.';
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return 'A link must start with https:// or http://.';
  if (/\s/.test(link)) return 'A link cannot contain spaces.';
  return null;
}

/** A leaked APK can be attacked offline, with no wait between guesses, so this is longer than the app needs. */
function checkCertificate(file) {
  if (!file) return null;
  if (!fs.existsSync(file)) return 'That file was not found.';
  return fs.readFileSync(file, 'utf8').includes('BEGIN CERTIFICATE') ? null : 'That is not a PEM certificate file.';
}

function checkPin(pin) {
  return /^\d{8,12}$/.test(pin) ? null : 'Use 8 to 12 digits.';
}

/** The address only, so a swapped link shows up without the token ever being printed. */
function shown(link) {
  const url = new URL(link);
  return `${url.origin}${url.pathname}${url.search ? ' (the rest is hidden)' : ''}`;
}

function hiddenPrompt(question) {
  return new Promise((resolve) => {
    const { stdin, stdout } = process;
    stdout.write(question);
    stdin.setRawMode(true);
    stdin.resume();
    stdin.setEncoding('utf8');
    let value = '';
    const onData = (chunk) => {
      for (const character of chunk.replace(/\x1b\[20[01]~/g, '')) {
        if (character === '\u0003') {
          stdout.write('\n');
          process.exit(130);
        }
        if (character === '\r' || character === '\n') {
          stdin.setRawMode(false);
          stdin.pause();
          stdin.removeListener('data', onData);
          stdout.write('\n');
          resolve(value.trim());
          return;
        }
        if (character === '\b' || character === '\u007f') value = value.slice(0, -1);
        else value += character;
      }
    };
    stdin.on('data', onData);
  });
}

async function collect() {
  const piped = process.stdin.isTTY ? null : fs.readFileSync(0, 'utf8').split(/\r?\n/);
  const ask = async (question, check) => {
    for (;;) {
      const answer = piped ? (piped.shift() || '').trim() : await hiddenPrompt(question);
      const problem = check(answer);
      if (!problem) return answer;
      if (piped) throw new Error(problem);
      console.log(`  ${problem}`);
    }
  };
  console.log('Nothing you type or paste here is shown, saved or sent. Press Enter after each one.\n');
  const activateUrl = await ask('Paste the ON link:  ', checkLink);
  console.log(`  ${shown(activateUrl)}`);
  const clearUrl = await ask('Paste the OFF link: ', checkLink);
  console.log(`  ${shown(clearUrl)}`);
  const pin = await ask('Choose a setup PIN, 8 to 12 digits:  ', checkPin);
  await ask('Type the PIN again:                    ', (again) => (again === pin ? null : 'That does not match. Type the PIN again.'));
  const file = await ask('Server certificate file, or Enter if none: ', checkCertificate);
  return { activateUrl, clearUrl, pin, certificate: file ? fs.readFileSync(file, 'utf8') : '' };
}

function versionName() {
  return fs.readFileSync(path.join(ROOT, 'app', 'build.gradle.kts'), 'utf8').match(/versionName = "([^"]+)"/)[1];
}

async function main() {
  const flag = process.argv.indexOf('--out');
  const output = flag > 0 ? process.argv[flag + 1] : path.join(ROOT, 'dist', `CellAlertHook-${versionName()}-preset.apk`);
  const { activateUrl, clearUrl, pin, certificate } = await collect();

  const folder = fs.mkdtempSync(path.join(os.tmpdir(), 'cellalerthook-preset-'));
  try {
    fs.writeFileSync(path.join(folder, ASSET), JSON.stringify(seal(activateUrl, clearUrl, pin, ITERATIONS, certificate)));
    console.log('\nBuilding. This takes a few minutes.');
    const env = { ...process.env, CELLALERTHOOK_PRESET_DIR: folder };
    const gradle = (args, stdio) => (process.platform === 'win32'
      ? spawnSync('cmd.exe', ['/c', '.\\gradlew.bat', ...args], { cwd: ROOT, env, stdio })
      : spawnSync('./gradlew', args, { cwd: ROOT, env, stdio }));
    const build = gradle([':app:assembleRelease', '--console=plain', '-q'], 'inherit');
    gradle(['--stop'], 'ignore');
    if (build.status !== 0) throw new Error('The build failed');
  } finally {
    fs.rmSync(folder, { recursive: true, force: true });
  }

  const built = path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
  if (!fs.existsSync(built)) throw new Error('No signed APK was made. Check CELLALERTHOOK_SIGNING_FILE.');
  if (!fs.readFileSync(built).includes(`assets/${ASSET}`)) throw new Error('The sealed links did not reach the APK');
  fs.mkdirSync(path.dirname(output), { recursive: true });
  fs.copyFileSync(built, output);
  fs.rmSync(built);
  const hash = crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex').slice(0, 16);
  console.log(`\nDone: ${output}\nSHA-256 starts ${hash}. Keep the PIN safe: each phone needs it once, and nobody can recover it.`);
}

if (require.main === module) {
  main().catch((error) => {
    console.error(`build-preset-apk: ${error.message}`);
    process.exit(1);
  });
}

module.exports = { seal, checkLink, checkPin, checkCertificate, shown };
