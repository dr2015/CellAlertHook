'use strict';
const assert = require('node:assert');
const crypto = require('node:crypto');
const { test } = require('node:test');
const { seal, checkLink, checkPin, shown } = require('../build-preset-apk');

const ON = 'https://192.168.1.50/on?key=not-a-real-key';
const OFF = 'https://192.168.1.50/off?key=not-a-real-key';

function open(sealed, pin) {
  const salt = Buffer.from(sealed.salt, 'base64');
  const body = Buffer.from(sealed.data, 'base64');
  const key = crypto.pbkdf2Sync(pin, salt, sealed.iterations, 32, 'sha256');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(sealed.iv, 'base64'));
  decipher.setAuthTag(body.subarray(body.length - 16));
  return Buffer.concat([decipher.update(body.subarray(0, body.length - 16)), decipher.final()]).toString('utf8');
}

test('the right PIN opens the seal', () => {
  assert.deepStrictEqual(JSON.parse(open(seal(ON, OFF, '24681357', 1000), '24681357')), { activateUrl: ON, clearUrl: OFF, certificate: '' });
});

test('a certificate travels inside the seal', () => {
  assert.strictEqual(JSON.parse(open(seal(ON, OFF, '24681357', 1000, 'fake certificate'), '24681357')).certificate, 'fake certificate');
});

test('a wrong PIN does not', () => {
  assert.throws(() => open(seal(ON, OFF, '24681357', 1000), '24681358'));
});

test('the seal does not contain the links', () => {
  const text = JSON.stringify(seal(ON, OFF, '24681357', 1000));
  assert.ok(!text.includes('not-a-real-key') && !text.includes('192.168.1.50'));
  assert.ok(!text.includes(Buffer.from('not-a-real-key').toString('base64')));
});

test('two seals of the same links look different', () => {
  assert.notStrictEqual(seal(ON, OFF, '24681357', 1000).data, seal(ON, OFF, '24681357', 1000).data);
});

test('a PIN must be 8 to 12 digits', () => {
  assert.strictEqual(checkPin('24681357'), null);
  assert.strictEqual(checkPin('246813579012'), null);
  assert.ok(checkPin('2468135'));
  assert.ok(checkPin('2468135790123'));
  assert.ok(checkPin('2468135a'));
});

test('only http and https links are accepted', () => {
  assert.strictEqual(checkLink(ON), null);
  assert.ok(checkLink('192.168.1.50/on'));
  assert.ok(checkLink('ftp://192.168.1.50/on'));
});

test('what is printed leaves out the token', () => {
  assert.strictEqual(shown(ON), 'https://192.168.1.50/on (the rest is hidden)');
  assert.ok(!shown(ON).includes('not-a-real-key'));
});
