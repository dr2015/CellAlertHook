# Test receiver

A small test server with no dependencies. It does two jobs:

- It stands in for the system you control, so the app can call a URL without anything real happening.
- It acts as an event log server, storing events and returning acknowledgments.

It takes no action on any alert. You do not need it in normal use.

```bash
CELLALERTHOOK_TOKEN=<device-token> node server.js --port 8787
```

On Windows PowerShell:

```powershell
$env:CELLALERTHOOK_TOKEN = "<device-token>"; node server.js --port 8787
```

The token is required, even if you only use the URL trigger routes. Add `--tls-cert cert.pem --tls-key key.pem` to serve HTTPS. Events are journaled to `data/events.ndjson`.

## URL trigger routes

These need no token.

| URL | Purpose |
|---|---|
| `/hook/on` | Use as the on URL |
| `/hook/off` | Use as the off URL |
| `/hook-log` | The list of recorded calls |

Any request under `/hook/` is recorded and answered with `200`.

## Event log routes

All of these need `Authorization: Bearer <token>`.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/emergency/health` | Connection check |
| `POST` | `/api/emergency/alerts` | Real alerts. Refuses `simulation: true`. |
| `POST` | `/api/emergency/test-alerts` | Simulations. Refuses `simulation: false`. |
| `POST` | `/api/emergency/heartbeat` | Device health |
| `GET` | `/api/emergency/events` | Stored events |

## Event log protocol

The event log is optional. A server of your own must follow these rules.

- **Acknowledge only after storing.** Return `200` with `{"ack": true, "eventId": "<same id>", "revision": <n>, "duplicate": <bool>}`. The app keeps retrying until it gets this.
- **Be idempotent.** The same `eventId` and `revision` is a repeat: return `ack: true, duplicate: true`.
- **Honour revisions.** The same `eventId` with a higher `revision` means the same incident with a stronger source, usually accessibility text confirmed by the system provider. Replace the record. Do not treat it as a new incident.
- **Keep routes separate.** A simulation must never be accepted on the production route, and a real alert never on the test route.
- **Check `testOrExercise`.** An official drill is a genuine broadcast, and must not be treated as a real emergency. `null` means unknown.
- **Do not treat `verified: true` as proof of origin.** It means the Android Cell Broadcast stack delivered the message on a retail build. Cell Broadcast has no signature, so that is not proof of government origin.
- **Check `textHidden`.** When it is `true`, `message` is a note from the app, not the alert: Android hid the text from the notification listener, and the other detectors were not working. Someone must read the alert on the phone. Never act on this message's words.

Return `4xx` other than 401, 403, 408 or 429 only for requests that retrying cannot fix, because the app stops retrying those. Anything else is retried with exponential backoff.

## Fault injection for tests

```bash
curl -X POST -H "Authorization: Bearer $CELLALERTHOOK_TOKEN" -H "Content-Type: application/json" \
  -d '{"failNext":3,"status":503}' http://localhost:8787/__mock/faults
```

| Field | Effect |
|---|---|
| `failNext`, `status` | Answer the next N alert requests with this status |
| `dropNext` | Close the socket on the next N requests |
| `badAckNext` | Store the event, then answer `ack: false` |
| `delayMs` | Delay every request |
| `hookFailPath`, `hookFailNext` | Answer the next N requests to this `/hook/` path with `503` |

`POST /__mock/reset` clears everything, and `GET /__mock/state` shows faults, heartbeats and recent requests.

## Tests

```bash
npm test
```
