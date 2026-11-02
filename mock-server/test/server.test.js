'use strict';

const assert = require('node:assert/strict');
const { after, before, beforeEach, test } = require('node:test');
const { createMockServer, ROUTES, validateAlert } = require('../server');

const TOKEN = 'test-token';
let baseUrl;
let mock;

function alert(overrides = {}) {
  return {
    eventId: 'event-1',
    deviceId: 'cellalerthook-receiver-01',
    timestamp: '2026-11-01T12:00:00Z',
    source: 'android_cell_broadcast',
    captureMethod: 'cell_broadcast_provider',
    originPackage: 'com.google.android.cellbroadcastservice',
    title: null,
    message: 'تنبيه طوارئ',
    language: 'ar',
    severity: 'unknown',
    verified: true,
    simulation: false,
    revision: 1,
    ...overrides,
  };
}

const simulated = (overrides = {}) =>
  alert({ source: 'simulation', captureMethod: 'simulation', verified: false, simulation: true, ...overrides });

function post(path, body, token = TOKEN) {
  return fetch(baseUrl + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

before(async () => {
  mock = createMockServer({ token: TOKEN, log: () => {} });
  await new Promise((resolve) => mock.server.listen(0, '127.0.0.1', resolve));
  baseUrl = `http://127.0.0.1:${mock.server.address().port}`;
});

after(() => new Promise((resolve) => mock.server.close(resolve)));

beforeEach(async () => {
  await post(ROUTES.reset, {});
});

test('a wrong token is refused and nothing is stored', async () => {
  const response = await post(ROUTES.alerts, alert(), 'wrong');
  assert.equal(response.status, 401);
  assert.equal(mock.events.size, 0);
});

test('a valid alert is acknowledged with its event id', async () => {
  const response = await post(ROUTES.alerts, alert());
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ack: true, eventId: 'event-1', revision: 1, duplicate: false });
  assert.equal(mock.events.get('event-1').message, 'تنبيه طوارئ');
});

test('delivery is idempotent by event id', async () => {
  await post(ROUTES.alerts, alert());
  const second = await (await post(ROUTES.alerts, alert())).json();
  assert.equal(second.duplicate, true);
  assert.equal(mock.events.size, 1);
  assert.equal(mock.events.get('event-1').duplicateDeliveries, 1);
});

test('a higher revision replaces the record and a stale one does not', async () => {
  await post(ROUTES.alerts, alert({ source: 'android_system_alert_ui', captureMethod: 'accessibility', verified: false }));
  const upgraded = await (await post(ROUTES.alerts, alert({ revision: 2 }))).json();
  assert.equal(upgraded.duplicate, false);
  assert.equal(mock.events.get('event-1').verified, true);
  assert.deepEqual(mock.events.get('event-1').previousRevisions, [1]);

  const stale = await (await post(ROUTES.alerts, alert({ revision: 1, verified: false, source: 'android_system_alert_ui' }))).json();
  assert.equal(stale.duplicate, true);
  assert.equal(mock.events.get('event-1').verified, true);
});

test('a simulation cannot use the production route', async () => {
  const response = await post(ROUTES.alerts, simulated());
  assert.equal(response.status, 422);
  assert.deepEqual(await response.json(), { error: 'simulation_on_production_route' });
  assert.equal(mock.events.size, 0);
});

test('a real alert cannot use the test route', async () => {
  const response = await post(ROUTES.testAlerts, alert());
  assert.equal(response.status, 422);
  assert.equal(mock.events.size, 0);
});

test('a simulation on the test route is stored as a simulation', async () => {
  const response = await post(ROUTES.testAlerts, simulated());
  assert.equal(response.status, 200);
  assert.equal(mock.events.get('event-1').route, ROUTES.testAlerts);
});

test('verified is refused unless the source is the Cell Broadcast stack', () => {
  assert.ok(validateAlert(alert({ source: 'android_system_notification' })).includes('verified_requires_cell_broadcast_source'));
  assert.ok(validateAlert(simulated({ verified: true })).includes('simulation_cannot_be_verified'));
});

test('malformed input is rejected rather than stored', async () => {
  assert.equal((await post(ROUTES.alerts, '{not json')).status, 400);
  assert.equal((await post(ROUTES.alerts, { eventId: 'x' })).status, 422);
  assert.equal((await post(ROUTES.alerts, alert({ revision: 0 }))).status, 422);
  assert.equal(mock.events.size, 0);
});

test('an oversized body is refused', async () => {
  const response = await post(ROUTES.alerts, alert({ message: 'x'.repeat(70 * 1024) })).catch((error) => error);
  assert.ok(response instanceof Error || response.status === 413);
  assert.equal(mock.events.size, 0);
});

test('injected failures can be consumed one at a time', async () => {
  await post(ROUTES.faults, { failNext: 2, status: 503 });
  assert.equal((await post(ROUTES.alerts, alert())).status, 503);
  assert.equal((await post(ROUTES.alerts, alert())).status, 503);
  assert.equal((await post(ROUTES.alerts, alert())).status, 200);
});

test('a bad acknowledgment still stores, so the client retry is a duplicate', async () => {
  await post(ROUTES.faults, { badAckNext: 1 });
  assert.deepEqual(await (await post(ROUTES.alerts, alert())).json(), { ack: false });
  assert.equal((await (await post(ROUTES.alerts, alert())).json()).duplicate, true);
  assert.equal(mock.events.size, 1);
});

test('the server refuses to start without a token', () => {
  assert.throws(() => createMockServer({ token: '' }), /token is required/);
});
