'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { buildPdus, packSeptets, gsm7Septets, PDU_LENGTH } = require('../cb-inject');

const unpackUcs2 = (pdu) => {
  const body = [];
  for (let offset = 8; offset + 1 < pdu.length; offset += 2) {
    const unit = pdu.readUInt16BE(offset);
    if (unit === 0x0d) break;
    body.push(unit);
  }
  return String.fromCharCode(...body);
};

test('every page is exactly one 88-byte GSM CB PDU', () => {
  const pdus = buildPdus({ messageId: 4370, serial: 0xc120, language: 'ar', text: 'ا'.repeat(100) });
  assert.equal(pdus.length, 3);
  for (const pdu of pdus) assert.equal(pdu.length, PDU_LENGTH);
});

test('the header carries serial, message id, DCS and page numbering', () => {
  const pdus = buildPdus({ messageId: 4370, serial: 0xc120, language: 'ar', text: 'ا'.repeat(50) });
  assert.equal(pdus[0].readUInt16BE(0), 0xc120);
  assert.equal(pdus[0].readUInt16BE(2), 0x1112);
  assert.equal(pdus[0][4], 0x11);
  assert.equal(pdus[0][5], 0x12);
  assert.equal(pdus[1][5], 0x22);
});

test('the language indication is packed GSM 7-bit', () => {
  const [pdu] = buildPdus({ messageId: 4370, serial: 1, language: 'ar', text: 'x' });
  assert.equal(pdu.subarray(6, 8).toString('hex'), '6139');
});

test('Arabic text survives a page split unchanged', () => {
  const text = 'تنبيه طوارئ تجريبي من مختبر جوفلو. هذه رسالة اختبار فقط ولا تتطلب أي إجراء.';
  const pdus = buildPdus({ messageId: 4370, serial: 1, language: 'ar', text });
  assert.equal(pdus.map(unpackUcs2).join(''), text);
});

test('a surrogate pair is never split across pages', () => {
  const text = 'a'.repeat(39) + '😀' + 'b';
  const pdus = buildPdus({ messageId: 4370, serial: 1, language: 'ar', text });
  assert.equal(pdus.map(unpackUcs2).join(''), text);
});

test('septet packing matches the published GSM 03.38 example', () => {
  assert.equal(packSeptets(gsm7Septets('hello'), 5).toString('hex'), 'e8329bfd06');
});

test('plain English uses GSM 7-bit', () => {
  const [pdu] = buildPdus({ messageId: 4383, serial: 1, language: 'en', text: 'Test' });
  assert.equal(pdu[4], 0x01);
  assert.equal(pdu.length, PDU_LENGTH);
});

test('more than 15 pages is refused', () => {
  assert.throws(() => buildPdus({ messageId: 4370, serial: 1, language: 'ar', text: 'ا'.repeat(700) }), /15-page/);
});
