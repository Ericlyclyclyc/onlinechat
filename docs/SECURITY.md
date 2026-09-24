# Security

> Languages: **English** | [简体中文](zh/SECURITY.md)

This document describes the threat model, the mitigations baked into Online Chat,
and the checklist you should run through before exposing the platform to the
public internet.

---

## Threat model

| Asset | Attacker goal | Mitigation |
|-------|---------------|------------|
| TLS private key (`./ssl/privkey.pem`) | Impersonate the server, MITM chat | File lives only on the host, never leaves it. Permissions should be `600`. Optional passphrase supported via `tls.privateKeyPassword`. |
| Web account passwords | Credential stuffing, account takeover | PBKDF2-HMAC-SHA256 with per-account random 16-byte salt, configurable iteration count (default 210 000), constant-time comparison. Plaintext never written to disk or logs. |
| Auth tokens | Session hijack | HMAC-SHA256 signed, expiry embedded, secret auto-generated with 48 bytes of `SecureRandom` and stored next to the accounts file. Rotating the secret invalidates all tokens. A token issued before the account's `lastLoginAt` is rejected, so changing the password (web page or `/onlinechat account setpassword`) signs out every other device. |
| Minecraft ⇄ web binding | Impersonate a player on the web chat | Requires the MC player to be **online** and to click a `[Yes]` button in game (which runs `/onlinechat bind confirm <code>`). Codes are 6 chars from an unambiguous alphabet, TTL-bounded, and single-use. `confirm()` also verifies the **clicking player's UUID** matches the pending request, so nobody can confirm a binding aimed at another player. Bind requests are rate-limited per web account (`limits.bindRequestsPerMinute`). |
| Stolen / cracked Minecraft account joining the server | Play as (and chat as) a legitimate player | Optional **2FA on join** (`[twoFactor]`, per-player opt-in). The player is frozen on join — movement/reach attributes zeroed, interaction/attack/item/drop/chat/command events cancelled — and receives a link `publicUrl/2fa/auth/<token>`. The token is 32 bytes from `SecureRandom` (base64url), single-use, tied to the joining UUID and expiring after `timeoutSeconds`. The confirmation page only releases the player if the browser's `oc_token` cookie belongs to the account **bound to that exact player**; any other account, an explicit "not me", or the timeout kicks the player. Tokens are never written to disk or logs. |
| Chat injection | Send `§`-formatted or command-like text into the game | Web-side text has `§` replaced with `&`, newlines stripped, and length-capped before broadcast. Web messages are broadcast via `broadcastSystemMessage`, which does **not** fire `ServerChatEvent`, so command prefixes (`/…`) are never executed. |
| Stored XSS via the web UI | Run script in another user's browser using a crafted player name / message | Every author name and message body is HTML-escaped client-side before rendering. Served pages also carry a strict `Content-Security-Policy` (`script-src 'self'`, no inline scripts), plus `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and HSTS over TLS. |
| Denial of service | Exhaust server resources | Per-IP **and** global WebSocket connection caps, per-IP login/register limits, per-session chat-message limit, per-account bind limit (all shared across connections and keyed per IP/account, not per TCP connection). HTTP body capped at 1 MiB, WebSocket frame capped at 64 KiB, 120 s idle timeout, chat history ring-bounded. PBKDF2 hashing and account file writes are offloaded to a bounded worker pool so they never block the Netty event loop. |
| Cross-origin abuse / CSWSH | Drive-by calls or cross-site WebSocket hijacking from a malicious website | A single origin allow-list (`cors.allowedOrigins`) governs both REST (via CORS headers) and the WebSocket upgrade (via `Origin` validation before the handshake). The `oc_token` auth cookie is `HttpOnly`, `SameSite=Lax`, and `Secure` on the TLS listener. |
| Replay of chat history | Leak private conversation | History is only sent to authenticated sessions. |

---

## Data at rest

The mod writes these files (locations configurable):

| File | Contents | Sensitivity |
|------|----------|-------------|
| `onlinechat/accounts.json` | Username, PBKDF2 hash, salt, iteration count, bound MC UUID + name, 2FA flag, timestamps | Medium — a leak allows offline brute-force of passwords. |
| `onlinechat/accounts.json.bak` | One-generation backup of the previous good account set | Same as above. |
| `onlinechat/token.secret` | 48-byte HMAC secret (Base64-URL) | High — a leak lets an attacker forge tokens for any user. |
| `onlinechat/chat_history.jsonl` | Append-only chat archive (author, text, timestamp) | Medium — private conversations. |
| `config/onlinechat/web/` | Extracted copy of the web front-end (HTML/JS/CSS/locales) + hidden `.exist` marker | Low as data, **high as an attack surface**: anyone who can write here can inject script served to every user. Keep it owned by the server user, not world-writable. |
| `config/onlinechat-server.toml` | Ports, TLS paths, optional `tokenSecret` override, optional key passphrase | High if you set a passphrase or fixed secret here. |

The account and secret files are written atomically (temp file + `ATOMIC_MOVE`) and, on POSIX
filesystems, are automatically restricted to owner read/write (`0600`) — including the `.tmp` and
`.bak` siblings. On startup a corrupt `accounts.json` triggers automatic recovery from
`accounts.json.bak`; if both are unusable the files are left untouched for manual recovery rather
than being overwritten with an empty set. On non-POSIX filesystems (Windows NTFS) the permission
step is a silent no-op, so protect those files with NTFS ACLs instead.

Chat messages are kept in an in-memory ring (`storage.chatHistorySize`) and appended to
`storage.chatLogFile` for paging; delete the file if you do not want an archive.

Deleting a web account (self-service on the Account page, or `/onlinechat account delete`)
removes its record, its player binding, any pending bind request and any frozen 2FA session,
and force-closes its live sockets. The `.bak` file may still hold the previous generation
until the next save.

TLS material in `./ssl/` is never read by anything but `SslContextBuilder` at
server start; it is never transmitted, logged or embedded into the mod jar.

---

## Hardening checklist (public deployment)

* [ ] Use a certificate from a real CA (Let's Encrypt is free). Self-signed certs
      train users to click through warnings.
* [ ] `chmod 600 ./ssl/privkey.pem` (the mod already sets `0600` on `accounts.json` and
      `token.secret` automatically on POSIX; the TLS key is yours to protect).
* [ ] `chown` those files to the user running the Minecraft server, not root.
* [ ] Keep `./ssl/` and `./onlinechat/` out of version control — they are already listed in
      `.gitignore`; never force-add them.
* [ ] Set `auth.allowRegistration = false` once your initial users have signed up,
      or gate registration behind a reverse proxy.
* [ ] Raise `auth.pbkdf2Iterations` to 600 000 if the CPU budget allows (OWASP 2023).
* [ ] Lower `auth.tokenTtlMinutes` to 60–240 for higher-security deployments. The browser
      keeps the session in the `oc_token` cookie (and the front-end mirrors it in
      `localStorage`), so a shorter TTL only forces more frequent re-logins.
* [ ] Restrict `cors.allowedOrigins` to your actual front-end origin(s) instead of `*`. This
      now also locks down the WebSocket upgrade (`Origin` check), closing cross-site
      WebSocket hijacking.
* [ ] Put the server behind a reverse proxy (nginx, Caddy, Traefik) if you need
      HTTP/2, IP allow-lists, WAF rules, or centralized logging. Terminate TLS at
      the proxy and forward to `127.0.0.1:<port>` — set `host = "127.0.0.1"` in
      `onlinechat-server.toml` so the Netty listener is not exposed directly.
* [ ] Enable a host firewall and only open the HTTPS port to the internet.
* [ ] Do not enable `verboseLogging` in production — it prints IPs and usernames.
* [ ] Rotate `token.secret` (delete the file and restart) if you suspect a leak.
* [ ] Back up `onlinechat/accounts.json` — losing it means every user must
      re-register and re-bind. The mod keeps a rolling `accounts.json.bak`, but that is a
      one-generation safety net, not a substitute for real backups.
* [ ] Tune the `[limits]` block for your expected load (connections per IP / total,
      registrations per hour, chat messages per minute, bind requests per minute).
* [ ] If you enable `[twoFactor]`, set `publicUrl` to the exact HTTPS origin players use; a
      wrong origin means the cookie is not sent and every 2FA join fails. Keep `timeoutSeconds`
      short (default 120 s) — a frozen player still occupies a slot.
* [ ] Restrict write access to `storage.webDir` (`config/onlinechat/web`). Files there are served
      verbatim to every browser; treat them like your web root.

---

## What this mod does **not** do

* No TOTP / authenticator-app 2FA for the **web login** itself. The built-in 2FA protects the
  *Minecraft join* using the web session as the second factor; if you need a second factor for
  the web account too, add it in front of the API with a reverse proxy.
* No e-mail verification or password reset. There is no e-mail channel by design —
  the mod never makes outbound network calls. Users change their own password from the
  Account page; an operator can reset it with `/onlinechat account setpassword`.
* No per-message moderation, no mute, no ban. Handle abuse with existing
  Minecraft server tooling (ban the MC account; the web binding becomes useless).
* No message editing or deletion. History is a fixed ring buffer plus an append-only log.
* No federation with other servers or platforms (Discord, Matrix, …). The bridge
  is strictly Minecraft ↔ bundled web UI.
* No client-side mod requirement. Everything works with vanilla clients.

---

## Reporting an issue

If you find a security bug, do **not** open a public issue. Contact the maintainer
privately (see the mod's page on your distribution channel) with:

* A minimal reproduction
* The affected version (`mod_version` in `gradle.properties`)
* Whether the bug is exploitable remotely or requires an authenticated account

We aim to acknowledge within 72 hours and ship a fix within 14 days for critical
issues.
