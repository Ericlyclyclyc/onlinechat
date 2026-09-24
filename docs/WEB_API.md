# Web API reference

> Languages: **English** | [简体中文](zh/WEB_API.md)

The mod exposes two surfaces on the same HTTPS listener:

* A small **REST** API under `/api/*` for authentication, binding and status.
* A **WebSocket** endpoint at `/ws` for real-time chat and live binding updates.

The bundled web UI (`/`) uses exactly these APIs, so anything documented here is
safe to call from your own front-end.

All bodies are JSON, all responses include `Access-Control-Allow-Origin` according
to `cors.allowedOrigins` in `onlinechat-server.toml`.

---

## Authentication model

* Register or log in → receive a **token** (HMAC-SHA256 signed, `username.issuedAt.expiry.signature`).
  Three-part tokens issued by older versions (`username.expiry.signature`) remain valid until they expire.
* The server sets the token as an **`oc_token` cookie** (`HttpOnly; Secure; SameSite=Lax`)
  on `/api/login` and `/api/register`, so the bundled UI never touches the token in JS.
* Pass the token on every subsequent request in any of (checked in this order):
  * `Authorization: Bearer <token>` header (REST)
  * `X-Auth-Token: <token>` header (REST)
  * `oc_token` cookie (REST **and** WebSocket)
  * `{ "type":"auth", "token":"<token>" }` first WebSocket frame
* For the WebSocket, browsers cannot set custom headers on the upgrade request, so the
  server reads the token from the **cookie / Authorization header of the handshake** and
  authenticates the channel automatically — no explicit `auth` frame needed when using cookies.
* `POST /api/logout` clears the cookie (`Max-Age=0`) and drops any live WebSocket sessions
  belonging to that token.
* Tokens expire after `auth.tokenTtlMinutes` (default 1440 = 24 h); the cookie `Max-Age`
  matches. The client should re-login when it receives `401` or `auth_error`.

Tokens are stateless — no server-side session, so scaling and restarts are cheap.
Rotating `tokenSecret` (or deleting `onlinechat/token.secret`) invalidates every
outstanding token immediately.

> **Cookie flag note:** the `oc_token` cookie is marked `Secure`, so it is only sent over
> HTTPS. Serve the UI through the TLS listener (the default) — a plain-HTTP deployment will
> not persist the cookie.

---

## REST endpoints

### `GET /api/status`

Public. Used by the UI on load to render the header and pick up runtime style
config (prefix texts and colours).

```json
{
  "ok": true,
  "service": "onlinechat",
  "registration": true,
  "onlineWeb": 3,
  "onlinePlayers": 7,
  "maxPlayers": 20,
  "motd": "A Minecraft Server",
  "twoFactor": false,
  "style": {
    "inGamePrefixText": "[In Game]",
    "inGamePrefixColor": "#2ecc71",
    "webPrefixText": "[Web Chat]",
    "webPrefixColor": "#e67e22",
    "maxMessageLength": 500,
    "minPasswordLength": 8
  }
}
```

### `POST /api/register`

Public (unless `auth.allowRegistration = false`).

Request:
```json
{ "username": "alice", "password": "hunter2hunter2" }
```

Constraints:
* `username` must match `^[A-Za-z0-9_]{3,32}$` and be unique (case-insensitive).
* `password` length ≥ `auth.minPasswordLength`.

Response `200` (also sets the `oc_token` cookie):
```json
{ "ok": true, "token": "…", "username": "alice" }
```

Errors: `400` (validation), `403` (registration disabled), `409` (username taken).

### `POST /api/login`

Request:
```json
{ "username": "alice", "password": "hunter2hunter2" }
```

Response `200` (also sets the `oc_token` cookie):
```json
{
  "ok": true,
  "token": "…",
  "username": "alice",
  "bound": true,
  "mcName": "Steve",
  "mcUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5"
}
```

Errors: `400`, `401` (bad credentials), `429` (rate limit exceeded — see
`auth.maxLoginAttempts` / `auth.loginCooldownSeconds`).

### `POST /api/logout`

Requires auth (cookie or header). Clears the `oc_token` cookie and closes any live
WebSocket sessions for that account so the browser stops receiving pushes.

Response: `{ "ok": true }`.

### `GET /api/me`

Requires auth. Returns the caller's account record.

```json
{
  "ok": true,
  "username": "alice",
  "bound": true,
  "mcName": "Steve",
  "mcUuid": "…",
  "mcOnline": true,
  "twoFactorAvailable": false,
  "twoFactor": false,
  "createdAt": 1710432000000,
  "lastLoginAt": 1710514800000
}
```

`twoFactorAvailable` mirrors `twoFactor.enabled` in the server config; `twoFactor` is this
account's own opt-in flag.

### `POST /api/bind`

Requires auth. Requests a binding to the Minecraft player with the given name.
The player **must be online**; a clickable confirmation prompt is sent to them.

Request:
```json
{ "mcName": "Steve" }
```

Response `200`:
```json
{ "ok": true, "code": "X7K2QM", "expiresAt": 1710514920000, "mcName": "Steve" }
```

Errors:
* `400` — player not online, unknown, already bound to another account, or rebind
  disallowed. The response body carries a `hint` field explaining what to do.
* `401` — missing/invalid token.
* `503` — Minecraft server not fully started yet.

The client should now wait for a `bind_ok`, `bind_denied` or `bind_expired`
WebSocket event.

### `POST /api/unbind`

Requires auth. Removes the caller's binding (and turns off 2FA for the account, since it
no longer has a player to protect). No request body needed.

Response: `{ "ok": true }`.

### `POST /api/account/password`

Requires auth. Changes the caller's password.

Request:
```json
{ "current": "hunter2hunter2", "next": "correct-horse-battery" }
```

Response `200`: `{ "ok": true, "token": "…" }` — the new token is also written to the `oc_token`
cookie. Changing the password bumps `lastLoginAt`, which invalidates **every** outstanding token
and pushes a `force_logout` frame (`reason: "password_changed"`) to all live sockets of the
account, including the caller's; the fresh token in the response keeps the calling browser signed
in.

Errors: `400` (validation / too short), `401` (current password wrong).

### `POST /api/account/delete`

Requires auth. Permanently deletes the caller's account.

Request:
```json
{ "password": "hunter2hunter2" }
```

Side effects: any pending bind request is cancelled, the Minecraft binding is removed, a frozen
2FA session for that player is released, live sockets receive `force_logout`
(`reason: "account_deleted"`) and are closed, and the cookie is cleared.

Response: `{ "ok": true }`. Errors: `401` (password wrong).

### `POST /api/2fa/toggle`

Requires auth. Player-side opt in/out of two-factor join protection.

Request: `{ "enabled": true }`
Response: `{ "ok": true, "twoFactor": true }`

Errors: `403` (feature disabled by the admin), `400` (enabling without a bound player).

### `GET /api/2fa/info?token=<token>`

Public (the answer depends on whether the caller is signed in). Everything the confirmation page
needs to render for a pending 2FA request:

```json
{
  "ok": true,
  "available": true,
  "valid": true,
  "authenticated": true,
  "username": "alice",
  "playerName": "Steve",
  "expiresAt": 1710514920000,
  "matches": true
}
```

* `available` — `twoFactor.enabled` in the config.
* `valid` — the token refers to a player who is currently frozen and waiting.
* `authenticated` / `username` — whether the request carried a usable `oc_token` cookie and for whom.
* `matches` — the signed-in account is the one bound to the waiting player. Only then can
  `/api/2fa/verify` succeed.

### `POST /api/2fa/verify`

Requires auth. "Yes, it's me": releases the frozen player behind the token.

Request: `{ "token": "…" }`

| Status | `result` | Meaning |
|--------|----------|---------|
| `200` | `ok` | Player released. Token is single-use and now dead. |
| `404` | `invalid_token` | Unknown, already used or expired token. |
| `403` | `wrong_account` | Cookie belongs to a different account — the player was **kicked**. |
| `503` | `unavailable` | Server not ready. |

### `POST /api/2fa/reject`

Requires auth. "That's not me": kicks the player waiting behind the token.

Request: `{ "token": "…" }` — Response: `{ "ok": true }`, or `404` if the token is not pending.

### `GET /api/history`

Requires auth. Returns the in-memory chat history (up to `storage.chatHistorySize`
messages, oldest first).

```json
{
  "ok": true,
  "messages": [
    { "type": "chat", "ts": 1710514800000, "author": "Steve", "authorUuid": "…", "text": "hello" },
    { "type": "web",  "ts": 1710514801500, "author": "alice", "authorUuid": "…", "text": "hi" },
    { "type": "system", "ts": 1710514802000, "text": "Steve joined the game", "systemKind": "join" }
  ]
}
```

### `GET /api/online`

Public. Lists in-game players and the total web-user count.

```json
{
  "ok": true,
  "players": [{ "name": "Steve", "uuid": "…" }],
  "webOnline": 3
}
```

### Static files

Everything not under `/api/` or `/ws` is served as a static file. Each request is resolved
**first against `storage.webDir` on disk** (default `config/onlinechat/web`, extracted from the
jar on first start — see [CONFIGURATION.md](CONFIGURATION.md#customising-the-web-front-end-webdir))
and falls back to the copy bundled in the jar's `/web/` folder. The UI is a **multi-page** app:

| URL | File | Purpose |
|-----|------|---------|
| `/` | `web/index.html` | Landing page — redirects to `/chat.html` if signed in, else shows a sign-in entry. |
| `/login.html` | `web/login.html` | Sign-in / create-account (tabbed). Accepts `?next=` (return URL) and `?reason=` (`kicked`, `password_changed`, `account_deleted`) for the notice shown on arrival. |
| `/account.html` | `web/account.html` | Account page: Minecraft binding + live confirmation, 2FA toggle, change password, delete account. |
| `/bind.html` | — | Legacy URL, `302` → `/account.html`. |
| `/2fa/auth/<token>` | `web/2fa.html` | Two-factor confirmation page linked from in-game chat. The token is read from the path by `2fa.js`. |
| `/chat.html` | `web/chat.html` | Live chat bridge. |
| `/style.css` | `web/style.css` | Shared stylesheet. |
| `/common.js` | `web/common.js` | Shared library: API helpers, i18n, toast, modal, nav, auth. |
| `/index.js` `/login.js` `/account.js` `/2fa.js` `/chat.js` | `web/*.js` | Per-page logic. |
| `/locales/en.json` `/locales/zh-CN.json` | `web/locales/*.json` | UI translations. |

Unknown paths fall back to `index.html`. Pages guard themselves: `account.html` and
`chat.html` call `GET /api/me` on load and redirect to `/login.html?next=…` when the
cookie is missing or expired; `2fa.html` does the same via `/api/2fa/info` so the user lands
back on the confirmation page after signing in.

---

## WebSocket protocol (`wss://<host>/ws`)

All frames are UTF-8 JSON text frames. Binary frames are rejected.

### Handshake

1. Client connects to `wss://<host>/ws`. The browser automatically attaches the
   `oc_token` cookie to the upgrade request.
2. Server sends:
   ```json
   { "type": "ready", "requiresAuth": true }
   ```
3. **If the handshake carried a valid cookie / Authorization header**, the server
   authenticates immediately and sends `auth_ok` (followed by `history`) with no extra
   frame required.
4. Otherwise the client **must** send `auth` within a reasonable time (the connection has
   a 120 s read-idle timeout):
   ```json
   { "type": "auth", "token": "…" }
   ```
5. Server replies `auth_ok` and immediately follows with a `history` frame.

If authentication fails, the server sends `auth_error` and the client should
fall back to the login screen. The connection is **not** closed automatically —
the client may try again with a fresh token.

### Client → server

| `type` | Payload | Notes |
|--------|---------|-------|
| `auth` | `{ token }` | Authenticate the connection. |
| `chat` | `{ text }` | Send a chat message. Requires auth. Text is trimmed, newlines replaced with spaces, `§` escaped to `&`, and truncated to `format.maxWebMessageLength`. |
| `bind` | `{ mcName }` | Same semantics as `POST /api/bind`. |
| `unbind` | — | Same semantics as `POST /api/unbind`. |
| `history` | — | Re-request the message history. |
| `ping` | — | Server replies `pong` with `ts`. Useful for keep-alive in browsers that don't expose WebSocket ping frames. |

### Server → client

| `type` | Payload | Meaning |
|--------|---------|---------|
| `ready` | `{ requiresAuth:true }` | Handshake accepted. |
| `auth_ok` | `{ username, bound, mcName?, mcUuid? }` | Token accepted. |
| `auth_error` | `{ error }` | Token rejected. |
| `history` | `{ messages:[…] }` | Snapshot of the ring buffer. |
| `chat` | `{ ts, author, authorUuid, text }` | In-game chat from a Minecraft player. Prefix with `[In Game]` on render. |
| `web` | `{ ts, author, authorUuid?, text }` | Chat from another web user (never echoed back to the sender). Prefix with `[Web Chat]` on render. |
| `system` | `{ ts, text, systemKind }` | Join / quit / death / advancement / web-presence message. `systemKind` ∈ `join, quit, death, advancement, web`. |
| `bind_pending` | `{ code, mcName, expiresAt }` | Confirmation prompt sent to the MC player. Start a countdown. |
| `bind_ok` | `{ code, mcName }` | Player clicked **[Yes]** — binding is now stored. |
| `bind_denied` | `{ code, mcName, reason }` | Player clicked **[No]**. |
| `bind_expired` | `{ code, mcName }` | TTL elapsed without confirmation. |
| `bind_error` | `{ error }` | Binding could not be initiated (player offline, unknown, already bound, …). |
| `unbind_ok` | — | Binding removed. |
| `force_logout` | `{ reason }` | This account's tokens were superseded; the server closes the socket right after. `reason` ∈ `login_elsewhere, password_changed, account_deleted`. The UI redirects to `/login.html?reason=…` (except for a password change it initiated itself). |
| `server_shutdown` | — | The Minecraft server is stopping. Clients show a blocking notice and stop reconnecting. |
| `pong` | `{ ts }` | Response to `ping`. |
| `error` | `{ error }` | Generic error for malformed frames or unknown `type`. |

### Example session

```
→ { "type":"auth", "token":"YWxpY2U.MTcxMDUxNDgwMDAwMA.abc…" }
← { "type":"auth_ok", "username":"alice", "bound":false }
← { "type":"history", "messages":[ … ] }
→ { "type":"bind", "mcName":"Steve" }
← { "type":"bind_pending", "code":"X7K2QM", "mcName":"Steve", "expiresAt":1710514920000 }
← { "type":"bind_ok", "code":"X7K2QM", "mcName":"Steve" }
→ { "type":"chat", "text":"hello from the browser" }
← { "type":"chat", "ts":…, "author":"Steve", "authorUuid":"…", "text":"welcome!" }
```

---

## Error model

REST errors return a JSON body:
```json
{ "ok": false, "error": "Human readable message" }
```

WebSocket errors use the `error` or `*_error` frames listed above; the connection
stays open unless the underlying transport fails.

---

## Rate limits

| Surface | Limit | Configurable via |
|---------|-------|------------------|
| Login | `auth.maxLoginAttempts` failures per IP inside `auth.loginCooldownSeconds` | `onlinechat-server.toml` |
| WebSocket frame size | 64 KiB per text frame | hard-coded |
| HTTP body size | 1 MiB | hard-coded |
| Chat message length | `format.maxWebMessageLength` | `onlinechat-common.toml` |
| Idle WebSocket | 120 s without any inbound frame | hard-coded |
