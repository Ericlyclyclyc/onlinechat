# Configuration

> Languages: **English** | [简体中文](zh/CONFIGURATION.md)

Online Chat uses **two** configuration files, both created automatically on first
launch with sensible defaults.

| File | Scope | Contents |
|------|-------|----------|
| `config/onlinechat-common.toml`  | Global (loaded on both sides) | Chat-bridge behaviour, prefixes, formatting |
| `world/serverconfig/onlinechat-server.toml` *(dedicated)* | Server-side only | HTTPS listener, TLS material, authentication, storage, CORS |

> On 1.20.1 the server config is **always per-world**: a dedicated server reads
> `world/serverconfig/onlinechat-server.toml`, a single-player world uses
> `saves/<world>/serverconfig/`. (On the 1.21.1 / 1.21.8 branches it moved to `config/`.)

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
# Language used for every in-game message this mod sends (chat prompts, command feedback).
# Any language file shipped under assets/onlinechat/lang/ (en_us, zh_cn). Unknown keys fall back to en_us.
language = "en_us"
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
    certDir = "./ssl"
    certFileName = "fullchain.pem"
    keyFileName = "privkey.pem"
    certChainPath = ""
    privateKeyPath = ""
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
    chatHistorySize = 300
    chatPageSize = 30
    chatLogFile = "onlinechat/chat_history.jsonl"
    webDir = "config/onlinechat/web"

[twoFactor]
    enabled = false
    publicUrl = ""
    timeoutSeconds = 120

[cors]
    allowedOrigins = ["*"]

[limits]
    maxConnectionsPerIp = 8
    maxConnectionsTotal = 200
    maxChatMessagesPerMinute = 20
    registerAttemptsPerHour = 5
    bindRequestsPerMinute = 3

verboseLogging = false
```

### `[tls]`

The TLS material is located in two steps. First, if `certChainPath` / `privateKeyPath` are set to a
non-blank value they are used verbatim (absolute, or relative to the run directory). Otherwise the
file is built from `certDir` + the matching file name (`<certDir>/<certFileName>` and
`<certDir>/<keyFileName>`). With the shipped defaults this resolves to `./ssl/fullchain.pem` and
`./ssl/privkey.pem`.

| Key | Default | Notes |
|-----|---------|-------|
| `certDir` | `./ssl` | Directory holding the PEM files. Relative paths resolve against the Minecraft run directory; absolute paths are used verbatim. This is the simplest knob — point it at your Let's Encrypt `live/<domain>/` folder, for instance. |
| `certFileName` | `fullchain.pem` | Certificate file inside `certDir` — the leaf certificate **and** any intermediates. Let's Encrypt's `fullchain.pem` is exactly this. |
| `keyFileName` | `privkey.pem` | Private key file inside `certDir`. PKCS#8 (`BEGIN PRIVATE KEY`) is preferred; PKCS#1 (`BEGIN RSA PRIVATE KEY`) also works with Netty ≥ 4.1.71 (bundled with MC 1.21.1). |
| `certChainPath` | *(blank)* | Optional explicit path override for the certificate. When non-blank it wins over `certDir` + `certFileName`. |
| `privateKeyPath` | *(blank)* | Optional explicit path override for the private key. When non-blank it wins over `certDir` + `keyFileName`. |
| `privateKeyPassword` | *(blank)* | Passphrase if the key is encrypted. Leave empty for unencrypted keys. |
| `requireClientAuth` | `false` | Enable mutual TLS. Almost never needed — turn on only if you issue client certificates. |

All relative paths (both `certDir` and the explicit overrides) resolve against the **working
directory** of the Minecraft process. Absolute paths are used verbatim.

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

### `language`

Every in-game line the mod produces (bind prompt, `/onlinechat` feedback, 2FA notices, join/quit
lines bridged to the web) is translated **server-side** from `assets/onlinechat/lang/<language>.json`,
so players need no client-side mod or resource pack. Shipped: `en_us`, `zh_cn`. Missing keys fall
back to `en_us`. Changes are picked up by `/onlinechat reload`.

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
| `chatHistorySize` | Number of recent messages kept in memory. Older messages are paged from `chatLogFile` on demand. |
| `chatPageSize` | Messages per page: the initial replay on WebSocket connect and each "scroll up" request. |
| `chatLogFile` | Append-only JSON Lines chat archive. |
| `webDir` | Directory the bundled web front-end is **extracted to and kept up to date** (see below). |

#### Customising the web front-end (`webDir`)

On the first server start the mod copies every file under its bundled `web/` folder (HTML, JS, CSS,
`locales/*.json`) to `webDir` (default `config/onlinechat/web`) and drops a hidden marker file
`.exist` there. The marker doubles as a **manifest**: it records the SHA-256 of every file as shipped
by the jar at the time it was written, which lets the mod tell your edits apart from stock files when
the mod is upgraded:

* Requests are served **from `webDir` first**; only files missing on disk fall back to the copies
  inside the jar. Edit `style.css`, tweak wording in `locales/zh-CN.json`, add a logo, and so on.
* On an upgrade (`.exist` present): files you never touched are refreshed to the new bundled version,
  files added by the new version are copied in, files the new version no longer ships are deleted
  (only when unmodified), and **files you edited are kept as-is**. If a file you edited also changed
  in the new release, a WARN lists it so you can merge the changes by hand.
* `locales/*.json` read from disk are additionally **key-merged** with the bundled copy, so a
  customised translation never misses keys introduced by a newer version.
* Delete `.exist` (or the whole directory) and restart to re-extract pristine defaults, overwriting
  every file.

### `[twoFactor]`

Optional second factor for **joining the Minecraft server**. It is off by default; when the admin
turns it on, each player decides for themselves whether to use it from the web **Account** page
(only possible once a Minecraft player is bound).

| Key | Default | Notes |
|-----|---------|-------|
| `enabled` | `false` | Master switch. When `false` nothing is frozen and the web toggle is greyed out. |
| `publicUrl` | *(blank)* | Base URL players' browsers can reach, e.g. `https://play.example.com:8443` (no trailing slash). Prepended to `/2fa/auth/<token>` in the chat link. Blank falls back to `https://<host or local IP>:<port>`, which is rarely what you want on a public server. |
| `timeoutSeconds` | `120` | How long a frozen player has to confirm in the browser before being kicked (15–600). |

Flow for a player whose bound web account has 2FA on:

1. On join the player is **frozen**: movement/jump/flight/gravity/reach attributes are zeroed,
   chat, commands, block/entity interaction, attacking, item use/drop/pickup and incoming damage are
   cancelled, and any GUI is closed. Other mods' own logic is unaffected (no Mixins are involved).
2. Chat shows a clickable one-time link `publicUrl + /2fa/auth/<32-byte random token>` plus a
   countdown reminder at 60/30/10 s.
3. Opening the link in a browser that holds the `oc_token` cookie of the **matching** web account
   shows the waiting player and two buttons: *Yes, it's me* (release) and *That's not me* (kick).
   A browser with no cookie is sent through the login page and back. A browser signed in as a
   **different** account cannot approve; trying to do so kicks the player.
4. Timeout → kick. Web server not running → 2FA is skipped with a WARN in the log so nobody is
   locked out permanently.

### `[cors]`

| Key | Notes |
|-----|-------|
| `allowedOrigins` | List of origins allowed to call the REST/WebSocket API. `["*"]` allows any origin — fine when the web UI is served by the same server, dangerous if you expose the API to a separate front-end. |

### `[limits]`

Abuse / resource-exhaustion limits. Every value accepts `0` to disable that specific limit.

| Key | Default | Notes |
|-----|---------|-------|
| `maxConnectionsPerIp` | `8` | Simultaneous WebSocket connections allowed from one IP. Handshakes beyond this are rejected with HTTP 429. |
| `maxConnectionsTotal` | `200` | Global cap on simultaneous WebSocket connections. Beyond it the handshake is rejected with HTTP 503. |
| `maxChatMessagesPerMinute` | `20` | Per-session chat throttle. The first excess message returns an error; further spam is dropped silently until the window resets. |
| `registerAttemptsPerHour` | `5` | `/api/register` calls per IP per hour (anti account-flooding). Excess attempts get HTTP 429. |
| `bindRequestsPerMinute` | `3` | Bind requests per web account per minute, enforced in `BindingManager` so it covers both the REST and WebSocket paths (anti popup-harassment of online players). |

> The login limiter (`maxLoginAttempts` / `loginCooldownSeconds` in `[auth]`) and these limits are
> **shared across all connections**. They are keyed per IP (or per account for binds), not per TCP
> connection, so reconnecting does not reset them.

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
| `/onlinechat status`              | any player | Show whether the player is bound and to which web account, and whether 2FA is on. |
| `/onlinechat unbind`              | any player | Remove your own binding (also turns off 2FA for that account). |
| `/onlinechat reload`              | op (level 2) | Restart the web listeners (HTTPS and, if enabled, HTTP) and reload config, language file + TLS material. |
| `/onlinechat account setpassword <username> <password>` | op (level 2) | Reset a web account's password. Signs the account out on every device. |
| `/onlinechat account delete <username>` | op (level 2) | Delete a web account: unbinds its player, turns off 2FA, closes its sessions. |

Players change their own password / delete their own account from the web **Account** page; the
two `account` sub-commands exist so an operator can help someone who is locked out.
