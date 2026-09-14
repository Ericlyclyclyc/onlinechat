# Configuration

Online Chat uses **two** configuration files, both created automatically on first
launch with sensible defaults.

| File | Scope | Contents |
|------|-------|----------|
| `config/onlinechat-common.toml`  | Global (loaded on both sides) | Chat-bridge behaviour, prefixes, formatting |
| `config/onlinechat-server.toml`  | Server-side only | HTTPS listener, TLS material, authentication, storage, CORS |

> On an integrated server (single-player), `onlinechat-server.toml` is written to
> `saves/<world>/serverconfig/` instead of `config/`.

Both files are hot-editable from the in-game **Mods → Online Chat → Config** screen
on the client. On a dedicated server, edit them by hand and run
`/onlinechat reload` (op-only) to restart the web listener without a full reboot.

---

## `onlinechat-common.toml`

```toml
# Master switch: bridge in-game chat to the web platform.
bridgeEnabled = true
# Bridge non-player chat messages (join, quit, death, advancement) to the web platform.
bridgeSystemMessages = true
# Which non-player messages should be bridged. Valid values: join, quit, death, advancement.
systemMessageKinds = ["join", "quit", "death", "advancement"]
# Broadcast a game-side chat line when a web user connects or disconnects from the WebSocket.
bridgeWebPresence = true

[prefixes]
    # Text shown before the sender name in the in-game chat when the message came from the web.
    webPrefixText = "[Web Chat]"
    # Colour of the [Web Chat] prefix. Any ChatColor name (GOLD, YELLOW, RED, ...). Orange-ish default: GOLD.
    webPrefixColor = "GOLD"
    # Text shown before the sender name on the web page when the message came from the game.
    inGamePrefixText = "[In Game]"
    # CSS colour applied to the [In Game] prefix on the web page.
    inGamePrefixColor = "#2ecc71"
    # CSS colour applied to the [Web Chat] prefix on the web page.
    webPrefixColorCss = "#e67e22"

[format]
    # Format for a web user's message relayed into the in-game chat.
    # Placeholders: {prefix} {name} {message}
    gameChatFormat = "{prefix} <{name}> {message}"
    # Strip Minecraft formatting codes (section-sign sequences) before forwarding a game message to the web.
    stripFormatting = true
    # Maximum length of a chat message a web user can send.
    maxWebMessageLength = 500
```

### Prefixes

| Key | Meaning |
|-----|---------|
| `webPrefixText` | Text of the orange tag shown **in game** before a web user's name. |
| `webPrefixColor` | Minecraft `ChatFormatting` name applied to that tag. Any of `BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE`. |
| `inGamePrefixText` | Text of the green tag shown **on the web** before a game player's name. |
| `inGamePrefixColor` | CSS colour string (`#rrggbb`, `rgb()`, named colour, …) applied on the web. |
| `webPrefixColorCss` | CSS colour string applied on the web when replaying a web-user's message back to other web users. |

### Format placeholders

`gameChatFormat` supports three placeholders:

| Placeholder | Substituted with |
|-------------|-----------------|
| `{prefix}` | The coloured `[Web Chat]` tag |
| `{name}` | The sender's display name (bound MC name if bound, otherwise web username) |
| `{message}` | The raw message text (§ characters are escaped to `&` before broadcast) |

Example: `"{prefix} {name} » {message}"` renders as **[Web Chat] Steve » hello**.

---

## `onlinechat-server.toml`

```toml
# Start the embedded HTTPS/WebSocket server when the Minecraft server starts.
enabled = true
# Bind address of the HTTPS server. Use 0.0.0.0 to listen on all interfaces.
host = "0.0.0.0"
# TCP port of the HTTPS server (the default, encrypted listener).
port = 8443
# Also start a plain-HTTP (unencrypted) listener alongside HTTPS.
httpEnabled = false
# TCP port of the plain-HTTP listener. Only used when httpEnabled = true.
httpPort = 8080

[tls]
    certChainPath = "./ssl/fullchain.pem"
    privateKeyPath = "./ssl/privkey.pem"
    privateKeyPassword = ""
    requireClientAuth = false

[auth]
    allowRegistration = true
    minPasswordLength = 8
    pbkdf2Iterations = 210000
    tokenTtlMinutes = 1440
    tokenSecret = ""
    maxLoginAttempts = 8
    loginCooldownSeconds = 300

[binding]
    bindCodeTtlSeconds = 120
    allowRebind = true

[storage]
    accountsFile = "onlinechat/accounts.json"
    chatHistorySize = 200

[cors]
    allowedOrigins = ["*"]

verboseLogging = false
```

### `[tls]`

| Key | Notes |
|-----|-------|
| `certChainPath` | PEM file containing the leaf certificate **and** any intermediates. Let's Encrypt's `fullchain.pem` is exactly this. |
| `privateKeyPath` | PEM private key. PKCS#8 (`BEGIN PRIVATE KEY`) is preferred; PKCS#1 (`BEGIN RSA PRIVATE KEY`) also works with Netty ≥ 4.1.71 (bundled with MC 1.21.1). |
| `privateKeyPassword` | Passphrase if the key is encrypted. Leave empty for unencrypted keys. |
| `requireClientAuth` | Enable mutual TLS. Almost never needed — turn on only if you issue client certificates. |

Relative paths resolve against the **working directory** of the Minecraft process.
Absolute paths are used verbatim.

### Listeners: `host`, `port`, `httpEnabled`, `httpPort`

The server always tries to bring up the **HTTPS** listener on `host:port` (this is the
default and recommended surface). Independently, when `httpEnabled = true` it also binds a
**plain-HTTP** listener on `host:httpPort`, sharing the same Netty event loops and the same
REST/WebSocket API.

| Key | Default | Notes |
|-----|---------|-------|
| `host` | `0.0.0.0` | Bind address for **both** listeners. |
| `port` | `8443` | HTTPS port. Requires valid TLS material (see `[tls]`). |
| `httpEnabled` | `false` | Switch for the additional unencrypted HTTP listener. |
| `httpPort` | `8080` | HTTP port. Ignored unless `httpEnabled = true`. |

Behaviour notes:

* The two listeners are independent: if the TLS material is missing or invalid, the HTTPS
  listener fails and logs an error, but an enabled HTTP listener still comes up (and vice
  versa). The web server reports "running" as long as **at least one** listener bound.
* On the HTTPS listener the `oc_token` auth cookie is issued with the `Secure` flag; on the
  plain-HTTP listener the flag is **omitted** so browsers will store and send it over
  `http://`. The WebSocket handshake likewise advertises `wss://` on HTTPS and `ws://` on HTTP.
* **Security:** plain HTTP transmits credentials and chat in clear text. Only enable it
  behind a TLS-terminating reverse proxy or for isolated LAN testing — never expose it
  directly to the public internet.

### `[auth]`

| Key | Notes |
|-----|-------|
| `allowRegistration` | Set to `false` to close new sign-ups (existing accounts keep working). |
| `minPasswordLength` | Enforced server-side on `/api/register`. |
| `pbkdf2Iterations` | PBKDF2-HMAC-SHA256 iteration count. OWASP 2023 recommends ≥ 600 000 for SHA-256 if you can afford the CPU; 210 000 is a reasonable default that stays snappy on modest hardware. |
| `tokenTtlMinutes` | Lifetime of the signed token issued at login. |
| `tokenSecret` | HMAC secret. Leave blank to auto-generate a 48-byte random secret and persist it next to the accounts file (`onlinechat/token.secret`). Set a fixed value only if you need tokens to survive moving the accounts file. |
| `maxLoginAttempts` | Failed logins per IP inside the cooldown window. `0` disables the limiter. |
| `loginCooldownSeconds` | Length of the cooldown window. |

### `[binding]`

| Key | Notes |
|-----|-------|
| `bindCodeTtlSeconds` | How long the player has to click **[Yes]** before the code expires. |
| `allowRebind` | Whether an already-bound web account may request a new binding (still requires in-game confirmation). |

### `[storage]`

| Key | Notes |
|-----|-------|
| `accountsFile` | JSON file storing web accounts and bindings. Relative paths resolve against the working directory. |
| `chatHistorySize` | Number of recent messages kept in memory and replayed on WebSocket connect. `0` disables history. |

### `[cors]`

| Key | Notes |
|-----|-------|
| `allowedOrigins` | List of origins allowed to call the REST/WebSocket API. `["*"]` allows any origin — fine when the web UI is served by the same server, dangerous if you expose the API to a separate front-end. |

### `verboseLogging`

When `true`, every HTTP request and WebSocket auth event is logged at INFO level.
Turn on for debugging only — it is noisy.

---

## Reloading

* Editing `onlinechat-common.toml` takes effect **immediately** (values are read
  per-event).
* Editing `onlinechat-server.toml` takes effect after `/onlinechat reload` (op-only)
  or a full server restart. Reload re-reads the TLS material, so it also picks up a
  renewed Let's Encrypt certificate.

---

## In-game commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/onlinechat bind confirm <code>` | any player | Run automatically when the player clicks **[Yes]** in the binding prompt. |
| `/onlinechat bind deny <code>`    | any player | Run automatically when the player clicks **[No]**. |
| `/onlinechat status`              | any player | Show whether the player is bound and to which web account. |
| `/onlinechat unbind`              | any player | Remove your own binding. |
| `/onlinechat reload`              | op (level 2) | Restart the web listeners (HTTPS and, if enabled, HTTP) and reload config + TLS material. |
