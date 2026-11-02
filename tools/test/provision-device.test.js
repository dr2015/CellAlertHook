'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { withService } = require('../provision-device');

const SERVICE = 'app.cellalerthook/x.Service';

test('an empty list becomes just this service', () => {
  assert.equal(withService('null', SERVICE), SERVICE);
  assert.equal(withService('', SERVICE), SERVICE);
  assert.equal(withService(undefined, SERVICE), SERVICE);
});

test('another accessibility service on the phone is preserved', () => {
  assert.equal(withService('com.other/a.B', SERVICE), `com.other/a.B:${SERVICE}`);
});

test('running twice does not add the service twice', () => {
  assert.equal(withService(`com.other/a.B:${SERVICE}`, SERVICE), `com.other/a.B:${SERVICE}`);
});
