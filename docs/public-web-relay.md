# Legado public Web relay

The Cloudflare Worker and Durable Object implementation is maintained in the
separate private repository
[`Rimchars/legado-cloudflare-relay`](https://github.com/Rimchars/legado-cloudflare-relay).
This app repository contains the Android client, Web UI compatibility layer and
the shared security contract only.

This document defines the security and compatibility boundary for the optional
Cloudflare relay. The feature is disabled by default and must not change the
existing LAN Web service when it is disabled.

## Trust boundaries

- The Android app is the authority for all local data and performs a second
  authorization check even when the Worker has accepted a request.
- A device credential authenticates only the Android tunnel. It must never be
  included in a browser URL or returned to a viewer.
- A share credential authenticates a browser. It is scoped, expiring and
  independently revocable. Durable Object storage contains only a salted hash.
- Cloudflare terminates TLS and can see plaintext application data. End-to-end
  encryption is a separate protocol revision and must not be implied by the
  first release.
- Durable Objects store credentials and small metadata only. Book content,
  images, source data and request bodies are never persisted there.

## Public routes

- `GET /v1/device/connect` upgrades the authenticated Android client to WSS.
- `/d/{deviceId}/...` serves the remote Web UI and forwards authorized API
  traffic to the connected device.
- Share creation and revocation are device-authenticated control operations.
- An offline device returns `503`; a connected device that does not finish a
  request before the deadline returns `504`.

Unknown routes, unsupported methods, encoded path traversal, control
characters, hop-by-hop headers and oversized metadata are rejected before a
request reaches the device.

## Authorization scopes

The first release supports `read` shares only. Android and Worker allowlists
must both permit the request.

Read scope initially includes the Web assets and these API paths:

- `/getBookshelf`
- `/getChapterList`
- `/getBookContent`
- `/getReadConfig`

Source login data, imports, saves, deletes, progress writes, uploads and the
debug/search WebSockets are excluded until separately implemented and tested.
The legacy `/cover` and `/image` proxy routes are also excluded because their
caller-controlled `path` parameters are not an authorization boundary. Remote
images require a later opaque resource token bound to an already authorized
book or chapter.

## Tunnel protocol v1

The device owns one WSS connection. Multiple HTTP requests are multiplexed by
an unpredictable request ID and a connection epoch. A response from an older
epoch is discarded.

Control frames are bounded JSON text messages. Body data uses binary frames so
it is never base64 encoded. The protocol includes:

- `hello`, `challenge`, `authenticate`, `ready`
- `http_request`, `http_request_chunk`, `http_request_end`
- `http_response`, `http_response_chunk`, `http_response_end`, `http_error`
- `credit`, `ping`, `pong`, `cancel`
- reserved `ws_open`, `ws_data`, `ws_close` for a later compatible revision

Limits for protocol v1:

- 32 KiB maximum body chunk
- 32 KiB maximum control frame
- four concurrent forwarded requests per device
- 512 KiB maximum unconsumed data per request
- 32 MiB maximum request or response body
- 15 second response-start timeout and 60 second total timeout

Implementations use bounded queues and credit-based flow control. They must not
call `readBytes()`, create an unbounded channel or buffer a complete body in
memory. Cancellation from either side releases every pending request and body
stream.

## Authentication

- Device IDs contain at least 128 random bits.
- Device secrets contain 256 random bits and are encrypted with an
  AndroidKeyStore AES-GCM key before being placed in preferences.
- Device authentication uses a server challenge, timestamp/expiry, nonce and
  HMAC. A challenge is single use.
- Share secrets contain at least 192 random bits. Browser links place the secret
  in the URL fragment, never the query string.
- The Web UI exchanges the fragment secret for a short-lived Secure, HttpOnly,
  SameSite=Strict cookie and immediately removes the fragment from browser
  history.
- Authentication failures use constant-time digest comparison and do not reveal
  whether a device or share exists.

## Lifecycle and recovery

Durable Objects use the WebSocket Hibernation API. Reconnecting a device
invalidates the previous epoch. GET requests may be retried by the browser after
an explicit failure; mutating requests must never be automatically replayed.

The Android service uses exponential backoff with jitter, observes network loss
and replacement, and exposes its real state to the settings UI. Android 15 and
newer foreground-service limits must be reported to the user; the implementation
must not silently promise permanent background availability.

## Compatibility and rollout

- Protocol version and minimum compatible version are exchanged in `hello`.
- The feature remains off after upgrades and after restoring a backup.
- Secrets are excluded from app backups and diagnostic logs.
- No Room schema is changed by this feature.
- Worker Durable Object schema changes use explicit Wrangler migrations.
- Release builds keep the last published Android version unless an actual
  release is requested.
