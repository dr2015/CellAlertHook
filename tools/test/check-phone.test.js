'use strict';
const assert = require('node:assert/strict');
const { test } = require('node:test');
const { physicalDevices, chooseDevice, readResult, verdict } = require('../check-phone');

const LISTING = 'List of devices attached\nemulator-5554\tdevice\nR58M123\tdevice\n\n';

test('emulators are never chosen as the phone', () => {
  assert.deepEqual(physicalDevices(LISTING), [{ serial: 'R58M123', state: 'device' }]);
  assert.equal(chooseDevice(LISTING, null), 'R58M123');
});

test('a missing, unapproved or ambiguous phone gives a clear instruction', () => {
  assert.throws(() => chooseDevice('List of devices attached\nemulator-5554\tdevice\n', null), /No phone found/);
  assert.throws(() => chooseDevice('List of devices attached\nR58M123\tunauthorized\n', null), /tap Allow/);
  assert.throws(() => chooseDevice('List of devices attached\nA\tdevice\nB\tdevice\n', null), /More than one/);
});

test('alerts that were read give a yes', () => {
  const result = readResult('I CellAlertHookPoc: provider cellbroadcasts pkg=x\nI CellAlertHookPoc: query HISTORY OK rows=2\nI CellAlertHookPoc: ALERTS IN HISTORY: 2');
  assert.equal(result.alertCount, 2);
  assert.match(verdict(result, true), /^YES.*came from the network/);
});

test('alerts on a debuggable build are never called real', () => {
  const result = readResult('I CellAlertHookPoc: provider cellbroadcasts pkg=x\nI CellAlertHookPoc: query HISTORY OK rows=2\nI CellAlertHookPoc: ALERTS IN HISTORY: 2');
  assert.match(verdict(result, false), /may be lab injections/);
  assert.doesNotMatch(verdict(result, false), /real|came from the network/);
});

test('an empty history is never reported as a success or a failure', () => {
  const result = readResult('I CellAlertHookPoc: provider cellbroadcasts pkg=x\nI CellAlertHookPoc: query HISTORY OK rows=0\nI CellAlertHookPoc: ALERTS IN HISTORY: 0');
  assert.match(verdict(result), /^ACCESS WORKS, NOTHING STORED/);
});

test('a refused or missing history is reported as such', () => {
  const denied = readResult('I CellAlertHookPoc: provider cellbroadcasts pkg=x\nI CellAlertHookPoc: query HISTORY DENIED SecurityException: no');
  assert.match(verdict(denied), /^BLOCKED/);
  assert.match(verdict(readResult('I CellAlertHookPoc: provider cellbroadcasts NOT RESOLVED')), /^NOT POSSIBLE/);
});
