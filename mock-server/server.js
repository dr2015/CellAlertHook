#!/usr/bin/env node
'use strict';
/**
 * Mock of an alert event log server. It validates the wire protocol and nothing else:
 * it stores events and returns acknowledgments. It has no way to reach any real system, and
 * production logic must keep operator confirmation between an event and any action.
 *
 * Usage:
 *   CELLALERTHOOK_TOKEN=<token> node server.js [--port 8787] [--host 0.0.0.0] [--data-dir ./data]
 *                                       [--tls-cert cert.pem --tls-key key.pem]
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const https = require('node:https');
const path = require('node:path');

const MAX_BODY_BYTES = 64 * 1024;

const ROUTES = {
  health: '/api/emergency/health',
  alerts: '/api/emergency/alerts',
  testAlerts: '/api/emergency/test-alerts',
  heartbeat: '/api/emergency/heartbeat',
  events: '/api/emergency/events',
  faults: '/__mock/faults',
  state: '/__mock/state',
  reset: '/__mock/reset',
};

const REQUIRED_STRINGS = ['eventId', 'deviceId', 'timestamp', 'source', 'captureMethod', 'message', 'language', 'severity'];
const REQUIRED_BOOLEANS = ['verified', 'simulation'];

function validateAlert(body) {
  const problems = [];
  if (body === null || typeof body !== 'object' || Array.isArray(body)) return ['body_not_object'];
  for (const field of REQUIRED_STRINGS) {
    if (typeof body[field] !== 'string' || body[field].length === 0) problems.push(`missing_${field}`);
  }
  for (const field of REQUIRED_BOOLEANS) {
    if (typeof body[field] !== 'boolean') problems.push(`missing_${field}`);
  }
  if (typeof body.revision !== 'number' || !Number.isInteger(body.revision) || body.revision < 1) {
    problems.push('invalid_revision');
  }
  if (typeof body.timestamp === 'string' && Number.isNaN(Date.parse(body.timestamp))) {
    problems.push('invalid_timestamp');
  }
  // A claim of verification on UI text would defeat the whole origin model.
  if (body.verified === true && body.source !== 'android_cell_broadcast') {
    problems.push('verified_requires_cell_broadcast_source');
  }
  if (body.verified === true && body.simulation === true) {
    problems.push('simulation_cannot_be_verified');
  }
  return problems;
}

function tokensMatch(expected, supplied) {
  const a = crypto.createHash('sha256').update(expected).digest();
  const b = crypto.createHash('sha256').update(supplied ?? '').digest();
  return crypto.timingSafeEqual(a, b);
}

function createMockServer({ token, dataDir = null, tls = null, log = console.log }) {
  if (!token) throw new Error('A device token is required');

  const events = new Map();
  const state = {
    heartbeats: new Map(),
    faults: { failNext: 0, status: 503, dropNext: 0, badAckNext: 0, delayMs: 0, hookFailPath: null, hookFailNext: 0 },
    requests: [],
    hookCalls: [],
  };

  if (dataDir) fs.mkdirSync(dataDir, { recursive: true });
  const journal = dataDir ? path.join(dataDir, 'events.ndjson') : null;

  function persist(kind, record) {
    if (journal) fs.appendFileSync(journal, `${JSON.stringify({ kind, record })}\n`, 'utf8');
  }

  function json(res, status, body) {
    const text = JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) });
    res.end(text);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (chunk) => {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) {
          reject(Object.assign(new Error('body_too_large'), { status: 413 }));
          req.destroy();
          return;
        }
        chunks.push(chunk);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  function authorized(req) {
    const header = req.headers.authorization ?? '';
    const supplied = header.startsWith('Bearer ') ? header.slice('Bearer '.length) : null;
    return supplied !== null && tokensMatch(token, supplied);
  }

  function storeAlert(body, route) {
    const previous = events.get(body.eventId);
    if (previous && body.revision <= previous.revision) {
      previous.duplicateDeliveries += 1;
      log(`[duplicate] event=${body.eventId} revision=${body.revision}`);
      return { duplicate: true, revision: previous.revision };
    }
    const record = {
      ...body,
      route,
      receivedByServerAt: new Date().toISOString(),
      duplicateDeliveries: previous?.duplicateDeliveries ?? 0,
      previousRevisions: previous ? [...previous.previousRevisions, previous.revision] : [],
    };
    events.set(body.eventId, record);
    persist('alert', record);
    log(`[${body.simulation ? 'SIMULATED' : 'ALERT'}] event=${body.eventId} rev=${body.revision} method=${body.captureMethod} ` +
      `source=${body.source} verified=${body.verified} class=${body.alertClass ?? 'unknown'} lang=${body.language}`);
    log(`    "${body.message}"`);
    log('    no action taken: operator confirmation is required by the production server');
    return { duplicate: false, revision: body.revision };
  }

  async function handleAlert(req, res, route) {
    const raw = await readBody(req);
    if (state.faults.dropNext > 0) {
      state.faults.dropNext -= 1;
      req.socket.destroy();
      return;
    }
    if (state.faults.failNext > 0) {
      state.faults.failNext -= 1;
      json(res, state.faults.status, { error: 'injected_failure' });
      return;
    }
    let body;
    try {
      body = JSON.parse(raw);
    } catch {
      json(res, 400, { error: 'invalid_json' });
      return;
    }
    const problems = validateAlert(body);
    if (problems.length > 0) {
      json(res, 422, { error: 'invalid_alert', problems });
      return;
    }
    // Each route refuses the other's traffic, so neither can leak through a misconfiguration.
    if (route === ROUTES.alerts && body.simulation) {
      json(res, 422, { error: 'simulation_on_production_route' });
      return;
    }
    if (route === ROUTES.testAlerts && !body.simulation) {
      json(res, 422, { error: 'real_alert_on_test_route' });
      return;
    }
    const { duplicate, revision } = storeAlert(body, route);
    if (state.faults.badAckNext > 0) {
      state.faults.badAckNext -= 1;
      json(res, 200, { ack: false });
      return;
    }
    json(res, 200, { ack: true, eventId: body.eventId, revision, duplicate });
  }

  async function handleFaults(req, res) {
    const raw = await readBody(req);
    Object.assign(state.faults, JSON.parse(raw || '{}'));
    json(res, 200, state.faults);
  }

  /** Stands in for the system a URL controls. Most take no bearer token, so these take none. */
  function handlePa(req, res, url) {
    const failing = state.faults.hookFailPath === url.pathname && state.faults.hookFailNext > 0;
    const call = { at: new Date().toISOString(), method: req.method, path: url.pathname, query: url.search, status: failing ? 503 : 200 };
    state.hookCalls.push(call);
    persist('hook_call', call);
    log(`[pa] ${call.at} ${call.method} ${call.path} -> ${call.status}`);
    if (failing) {
      state.faults.hookFailNext -= 1;
      json(res, 503, { error: 'injected_failure' });
    } else {
      json(res, 200, { ok: true });
    }
  }

  async function route(req, res) {
    const url = new URL(req.url, 'http://mock');
    state.requests.push({ at: new Date().toISOString(), method: req.method, path: url.pathname });
    if (state.requests.length > 200) state.requests.shift();

    if (url.pathname.startsWith('/hook/')) {
      handlePa(req, res, url);
      return;
    }
    if (url.pathname === '/hook-log') {
      json(res, 200, { calls: state.hookCalls });
      return;
    }

    if (state.faults.delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, state.faults.delayMs));
    }
    if (!authorized(req)) {
      json(res, 401, { error: 'unauthorized' });
      return;
    }

    if (req.method === 'GET' && url.pathname === ROUTES.health) {
      json(res, 200, { status: 'ok', mode: 'mock', time: new Date().toISOString() });
    } else if (req.method === 'POST' && (url.pathname === ROUTES.alerts || url.pathname === ROUTES.testAlerts)) {
      await handleAlert(req, res, url.pathname);
    } else if (req.method === 'POST' && url.pathname === ROUTES.heartbeat) {
      const body = JSON.parse(await readBody(req));
      state.heartbeats.set(body.deviceId, { ...body, receivedAt: new Date().toISOString() });
      persist('heartbeat', body);
      json(res, 200, { ok: true });
    } else if (req.method === 'GET' && url.pathname === ROUTES.events) {
      json(res, 200, { events: [...events.values()] });
    } else if (req.method === 'POST' && url.pathname === ROUTES.faults) {
      await handleFaults(req, res);
    } else if (req.method === 'GET' && url.pathname === ROUTES.state) {
      json(res, 200, {
        faults: state.faults,
        heartbeats: [...state.heartbeats.values()],
        recentRequests: state.requests.slice(-40),
        hookCalls: state.hookCalls,
        eventCount: events.size,
      });
    } else if (req.method === 'POST' && url.pathname === ROUTES.reset) {
      events.clear();
      state.heartbeats.clear();
      state.requests.length = 0;
      state.hookCalls.length = 0;
      Object.assign(state.faults, { failNext: 0, status: 503, dropNext: 0, badAckNext: 0, delayMs: 0, hookFailPath: null, hookFailNext: 0 });
      json(res, 200, { ok: true });
    } else {
      json(res, 404, { error: 'not_found' });
    }
  }

  const listener = (req, res) => {
    route(req, res).catch((error) => {
      if (res.headersSent) {
        res.destroy();
      } else {
        json(res, error.status ?? 400, { error: error.message });
      }
    });
  };

  const server = tls ? https.createServer(tls, listener) : http.createServer(listener);
  return { server, events, state };
}

function parseArgs(argv) {
  const options = { port: 8787, host: '0.0.0.0', dataDir: path.join(__dirname, 'data'), cert: null, key: null };
  for (let i = 0; i < argv.length; i += 2) {
    const [flag, value] = [argv[i], argv[i + 1]];
    switch (flag) {
      case '--port': options.port = Number.parseInt(value, 10); break;
      case '--host': options.host = value; break;
      case '--data-dir': options.dataDir = value; break;
      case '--tls-cert': options.cert = value; break;
      case '--tls-key': options.key = value; break;
      default: throw new Error(`Unknown argument ${flag}`);
    }
  }
  return options;
}

if (require.main === module) {
  const options = parseArgs(process.argv.slice(2));
  const token = process.env.CELLALERTHOOK_TOKEN;
  if (!token) {
    console.error('Set CELLALERTHOOK_TOKEN to the device credential before starting the mock server.');
    process.exit(1);
  }
  const tls = options.cert && options.key
    ? { cert: fs.readFileSync(options.cert), key: fs.readFileSync(options.key) }
    : null;
  const { server } = createMockServer({ token, dataDir: options.dataDir, tls });
  server.listen(options.port, options.host, () => {
    console.log(`CellAlertHook mock receiver on ${tls ? 'https' : 'http'}://${options.host}:${options.port}`);
    console.log('This mock stores events only. It cannot activate anything.');
  });
}

module.exports = { createMockServer, validateAlert, ROUTES };
