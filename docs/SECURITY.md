# Security

This document describes the threat model, the mitigations baked into Online Chat,
and the checklist you should run through before exposing the platform to the
public internet.

---

## Threat model

| Asset | Attacker goal | Mitigation |
|-------|---------------|------------|
| TLS private key (`./ssl/privkey.pem`) | Impersonate the server, MITM chat | File lives only on the host, never leaves it. Permissions should be `600`. Optional passphrase supported via `tls.privateKeyPassword`. |
| Web account passwords | Credential stuffing, account takeover | PBKDF2-HMAC-SHA256 with per-account random 16-byte salt, configurable iteration count (default 210 000), constant-time comparison. Plaintext never written to disk or logs. |
| Auth tokens | Session hijack | HMAC-SHA256 signed, expiry embedded, secret auto-generated with 48 bytes of `SecureRandom` and stored next to the accounts file. Rotating the secret invalidates all tokens. |
| Minecraft ⇄ web binding | Impersonate a player on the web chat | Requires the MC player to be **online** and to click a `[Yes]` button in game (which runs `/onlinechat bind confirm <code>`). Codes are 6 chars from an unambiguous alphabet, TTL-bounded, and single-use. |
| Chat injection | Send `§`-formatted or command-like text into the game | Web-side text has `§` replaced with `&`, newlines stripped, and length-capped before broadcast. Web messages are broadcast via `broadcastSystemMessage`, which does **not** fire `ServerChatEvent`, so command prefixes (`/…`) are never executed. |
| Denial of service | Exhaust server resources | Per-IP login rate limiting, HTTP body capped at 1 MiB, WebSocket frame capped at 64 KiB, 120 s idle timeout, chat history ring-bounded, single-worker Netty event loop group. |
| Cross-origin abuse | Drive-by calls from a malicious website | CORS allow-list configurable; defaults to `*` (safe when the UI is served from the same origin because the browser then applies same-origin rules to cookies — no cookies are used). |
| Replay of chat history | Leak private conversation | History is only sent to authenticated sessions. |

---

## Data at rest

The mod writes three files (locations configurable):

| File | Contents | Sensitivity |
|------|----------|-------------|
| `onlinechat/accounts.json` | Username, PBKDF2 hash, salt, iteration count, bound MC UUID + name, timestamps | Medium — a leak allows offline brute-force of passwords. Protect with `chmod 600`. |
| `onlinechat/token.secret` | 48-byte HMAC secret (Base64-URL) | High — a leak lets an attacker forge tokens for any user. Protect with `chmod 600`. |
| `config/onlinechat-server.toml` | Ports, TLS paths, optional `tokenSecret` override, optional key passphrase | High if you set a passphrase or fixed secret here. |

Chat messages are **not** persisted to disk. History is an in-memory ring buffer
that disappears on server restart.

TLS material in `./ssl/` is never read by anything but `SslContextBuilder` at
server start; it is never transmitted, logged or embedded into the mod jar.

---

## Hardening checklist (public deployment)

* [ ] Use a certificate from a real CA (Let's Encrypt is free). Self-signed certs
      train users to click through warnings.
* [ ] `chmod 600 ./ssl/privkey.pem ./onlinechat/token.secret ./onlinechat/accounts.json`
* [ ] `chown` those files to the user running the Minecraft server, not root.
* [ ] Set `auth.allowRegistration = false` once your initial users have signed up,
      or gate registration behind a reverse proxy.
* [ ] Raise `auth.pbkdf2Iterations` to 600 000 if the CPU budget allows (OWASP 2023).
* [ ] Lower `auth.tokenTtlMinutes` to 60–240 for higher-security deployments and
      rely on the browser remembering the token in `localStorage`.
* [ ] Restrict `cors.allowedOrigins` to your actual front-end origin(s) instead of `*`.
* [ ] Put the server behind a reverse proxy (nginx, Caddy, Traefik) if you need
      HTTP/2, IP allow-lists, WAF rules, or centralized logging. Terminate TLS at
      the proxy and forward to `127.0.0.1:<port>` — set `host = "127.0.0.1"` in
      `onlinechat-server.toml` so the Netty listener is not exposed directly.
* [ ] Enable a host firewall and only open the HTTPS port to the internet.
* [ ] Do not enable `verboseLogging` in production — it prints IPs and usernames.
* [ ] Rotate `token.secret` (delete the file and restart) if you suspect a leak.
* [ ] Back up `onlinechat/accounts.json` — losing it means every user must
      re-register and re-bind.

---

## What this mod does **not** do

* No 2FA / TOTP. Add it in front of the API with a reverse proxy if you need it.
* No e-mail verification or password reset. There is no e-mail channel by design —
  the mod never makes outbound network calls.
* No per-message moderation, no mute, no ban. Handle abuse with existing
  Minecraft server tooling (ban the MC account; the web binding becomes useless).
* No message editing or deletion. History is a fixed ring buffer.
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
