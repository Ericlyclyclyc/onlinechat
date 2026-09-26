# Development guide

> Languages: **English** | [简体中文](zh/DEVELOPMENT.md)

A tour of the source tree, the runtime wiring, and the extension points you are
most likely to touch.

---

## Repository structure (branches)

One codebase, one branch per Minecraft generation — the three NeoForge lines differ too
much for a single jar (1.20.1 still uses `net.minecraftforge` namespaces; the event, config
and component APIs moved again between 21.1 and 26.1):

| Branch | Minecraft | NeoForge | Loader dep | Build JDK | Toolchain |
|--------|-----------|----------|-----------|-----------|-----------|
| **`master`** *(this branch)* | 1.21.1 | 21.1.233+ | `neoforge` | 21 | ModDevGradle 2.0.147 · Gradle 9.2.1 · Mojang mappings |
| `mc/1.21.8` | 1.21.8 | 26.1.2.109+ | `neoforge` | 25 | ModDevGradle 2.0.147 · Gradle 9.2.1 · Mojang mappings |
| `mc/1.20.1` | 1.20.1 | 47.1.106+ | `forge` | 17 | NeoGradle 6.0.21 · Gradle 8.1.1 · parchment 2023.09.03 |

Working rules:

* **Never merge build scripts across branches.** `build.gradle`, `gradle.properties`,
  `gradle/wrapper/*` and `settings.gradle` belong to one toolchain each (ModDevGradle vs
  NeoGradle 6); cherry-pick only Java / web-asset changes.
* The **web front-end and most Java code are shared** across branches — a fix like the
  SharedWorker socket patch is cherry-picked onto every branch verbatim.
* Version-specific Java divergences are small and isolated (config spec types, event
  names, attribute names, mods.toml location).
* Local release jars are kept in the git-ignored `release/` folder:
  `git checkout <branch>` → `.\gradlew.bat build` → copy the jar to `release/`.
* CI (`.github/workflows/build.yml`) picks the JDK per branch: 21 (`master`), 21 with
  toolchain 25 (`mc/1.21.8`), 17 (`mc/1.20.1`).

This branch's extras worth knowing:

* `netty-codec-http` 4.1.97 is embedded via JarInJar (MC 1.21.1 does not ship it), and is
  put on ModDevGradle's dev-only `additionalRuntimeClasspath` so `runServer`/`runClient`
  see it in the IDE workspace.
* The mods.toml is templated from `src/main/templates/META-INF/neoforge.mods.toml` by
  `generateModMetadata` (ModDevGradle convention) — unlike the 1.20.1 branch, which keeps a
  literal `src/main/resources/META-INF/mods.toml`.

---

## Project layout

```
src/main/java/net/mcless/dev/onlinechat/
├── OnlineChat.java               # @Mod entry — owns the runtime singletons
├── OnlineChatClient.java         # @Mod(client) — registers the config screen
├── account/
│   ├── Account.java              # POJO stored in accounts.json
│   ├── AccountManager.java       # Load/save/register/bind, indexed by name and UUID
│   ├── PasswordHasher.java       # PBKDF2-HMAC-SHA256 with per-account salt
│   └── TokenService.java         # Stateless HMAC-signed tokens
├── auth/
│   └── TwoFactorGuard.java       # 2FA join freeze: attribute/event lock, one-time tokens, timeout kick
├── bridge/
│   ├── BindingManager.java       # Pending bind codes, TTL, confirm/deny
│   ├── ChatBridge.java           # Event listeners + broadcast helpers + history
│   ├── MessageStore.java         # In-memory ring + JSONL archive, paged history
│   └── WebSessionManager.java    # Live WebSocket sessions keyed by channel and username
├── command/
│   └── OnlineChatCommand.java    # /onlinechat … command tree + clickable [Yes]/[No]
├── config/
│   ├── CommonConfig.java         # onlinechat-common.toml
│   └── ServerConfig.java         # onlinechat-server.toml
├── i18n/
│   └── Lang.java                 # Server-side translation of every in-game string
└── web/
    ├── HttpApiHandler.java       # REST + static + WebSocket handshake
    ├── RateLimiter.java          # Sliding-window per-key limiter
    ├── SslContexts.java          # PEM → Netty SslContext
    ├── WebAssets.java            # Extracts web/ to storage.webDir, disk-first static lookup
    ├── WebServer.java            # Netty bootstrap
    └── WebSocketFrameHandler.java# JSON frame protocol

src/main/resources/
├── assets/onlinechat/lang/
│   ├── en_us.json                # Server-side translations (chat prompts, command feedback, config)
│   └── zh_cn.json
└── web/                          # multi-page UI served by the embedded HTTPS server
    ├── index.html   index.js     # landing page — redirects to login or chat
    ├── login.html   login.js     # tabbed login / register
    ├── account.html account.js   # account page: MC binding (WebSocket-driven), 2FA toggle, password, delete
    ├── 2fa.html     2fa.js       # 2FA confirmation page served at /2fa/auth/<token>
    ├── chat.html    chat.js      # live chat bridge
    ├── common.js                 # shared API / i18n / auth / toast helpers
    ├── style.css                 # dark glassmorphism theme
    └── locales/en.json, zh-CN.json   # front-end UI dictionaries

src/main/templates/META-INF/neoforge.mods.toml   # processed by generateModMetadata
```

---

## Runtime wiring

```
ServerStartingEvent
      │
      ▼
OnlineChat.onServerStarting
      │  resolve run directory (working dir)
      │  Lang.load(ServerConfig.language)  ← server-side i18n dictionary
      │  new AccountManager(...).load()
      │  new TokenService(...).init()      ← generates token.secret on first run
      │  new MessageStore(...).load()      ← chat ring + JSONL archive
      │  new WebSessionManager()
      │  new ChatBridge(sessions, accounts, messages).setServer(mc)
      │  new BindingManager(accounts, bridge)
      │  new TwoFactorGuard(accounts).setServer(mc)
      │  NeoForge.EVENT_BUS.register(bridge); register(twoFactor)
      │  new WebAssets(webDir).extractIfNeeded()  ← copies web/ if <webDir>/.exist is absent, else manifest-driven upgrade
      │  new WebServer(...)                ← constructed, not started yet
      ▼
ServerStartedEvent
      │
      ▼
WebServer.start()
      │  SslContexts.buildServerContext(runDir)   ← reads <certDir>/*.pem (default ./ssl)
      │  Netty pipeline:
      │      SslHandler → IdleStateHandler → HttpServerCodec →
      │      HttpObjectAggregator → ChunkedWriteHandler →
      │      HttpApiHandler → WebSocketFrameHandler
      ▼
Ready. Clients connect to https://host:port/
```

On `ServerStoppingEvent` the pipeline is reversed: browsers get a `server_shutdown` frame,
the Netty channels are closed, event loop groups shut down gracefully, `ChatBridge` and
`TwoFactorGuard` are unregistered from the event bus, and `AccountManager.save()` flushes
any pending mutations.

---

## Key design decisions

### Why Netty (and not `com.sun.net.httpserver` or a third-party lib)?

Minecraft already bundles most of Netty 4.1.97.Final (`netty-handler`, `netty-transport`,
`netty-codec`, …) — except `netty-codec-http`, which contains the HTTP/WebSocket codecs this mod
needs. Using Netty means:

* **Zero extra runtime dependencies** — `netty-codec-http` is the one artefact shipped inside the
  mod jar via **JarInJar** (`jarJar(...) { transitive = false }` in `build.gradle`); everything else
  comes from Minecraft itself, so there are no duplicate Netty classes on the runtime classpath.
* Native support for the WebSocket protocol (`WebSocketServerHandshaker`,
  `TextWebSocketFrame`) and TLS (`SslContextBuilder.forServer(File, File)`).
* Battle-tested event-loop model, matching Minecraft's own network layer.

The compile classpath needs an explicit `compileOnly` on the Netty artefacts
(see `build.gradle`) because ModDevGradle does not re-export Minecraft's
transitive dependencies. At runtime the classes come from Minecraft itself, except
`netty-codec-http`, which is jarJar'd — and is additionally put on ModDevGradle's
dev-only `additionalRuntimeClasspath` configuration so `runServer` / `runClient`
work in the IDE workspace (on MC ≤ 1.21.8 the Gradle run configurations do not
see jarJar'd artefacts).

### Why stateless HMAC tokens and not sessions?

* No server-side state to lose on restart.
* Trivial to scale out (though this mod is single-server by nature).
* Rotating the secret invalidates every token in one step.

The one concession to revocation: a token whose `issuedAt` predates the account's
`lastLoginAt` is rejected. Changing the password (or deleting the account) bumps that
timestamp, so every other device is signed out without any server-side session table.

### Why `broadcastSystemMessage` for web → game?

`ServerChatEvent` fires only for messages that originate from a `ServerPlayer`.
Broadcasting via `MinecraftServer.getPlayerList().broadcastSystemMessage(component, false)`
skips that event entirely, which means:

* No echo loop back to the web.
* No accidental execution of `/command` prefixes (a vanilla chat pipeline quirk).
* Full control over the rendered `Component`, including the coloured prefix.

### Why a `[Yes]` click instead of a code typed in chat?

Chat-typed codes leak into logs, are visible to other players, and require the
user to switch focus. A `ClickEvent.Action.RUN_COMMAND` link runs a hidden
command with a single-use code — nobody else can see or replay it, and the
player's own client confirms the intent.

### Why attribute + event freezing for 2FA instead of Mixins / packet filtering?

`TwoFactorGuard` zeroes movement speed, jump strength, flying speed, gravity and reach via
`ADD_MULTIPLIED_TOTAL -1` modifiers, cancels interaction / attack / item-use / drop / command
events while frozen, and snaps the player back to the join position every tick. That is
pure NeoForge API: no Mixin into the network layer, so it cannot collide with mods that
replace the tick or chunk pipeline (Create, Sable, …). The trade-off is that the client
still receives world packets during the freeze — the player just cannot act on them.

Tokens are 32 random bytes (base64url), single-use, bound to the joining player's UUID,
and expire with `twoFactor.timeoutSeconds`. The web side confirms with the `oc_token`
cookie: the browser account must be the one bound to that player, otherwise the player
is kicked.

### Why translate on the server and not ship client lang files?

A server-side mod cannot assume every client has the mod (or a resource pack) installed.
`Lang` loads `assets/onlinechat/lang/<language>.json` from the jar and produces literal
`Component`s, so vanilla clients see the translated text. `en_us` is the fallback for
missing keys.

---

## Extending the mod

### Add a new bridged system event

1. Add a kind string to the allow-list in `CommonConfig.SYSTEM_MESSAGE_KINDS`.
2. Add a `@SubscribeEvent` handler in `ChatBridge`, gated on
   `shouldBridge("<yourKind>")`.
3. Emit with `rememberAndBroadcast(new ChatMessage(..., Kind.SYSTEM, ..., "<yourKind>"))`.
4. Optionally teach the web UI to render it specially (see `appendMessage` in
   `web/chat.js`).

### Add a new REST endpoint

1. Add a case in `HttpApiHandler.handleApi`'s switch.
2. Implement a `handleXxx(ChannelHandlerContext, FullHttpRequest)` method.
3. Use `auth(req)` to require a token, or skip it for public endpoints.
4. Reply with `sendJson(ctx, req, HttpResponseStatus.OK, jsonObject)`.

### Add a new WebSocket message type

1. Extend the switch in `WebSocketFrameHandler.channelRead0`.
2. Update `docs/WEB_API.md` with the new frame shape.
3. Handle the new type in the relevant page script (e.g. `web/chat.js`).

### Swap the storage backend

`AccountManager` is the only place that touches disk. Replace its `load()`/`save()`
with SQLite, MariaDB or an HTTP call — the rest of the codebase uses the in-memory
`Account` POJOs and never sees the storage layer.

### Custom web UI

You do not need to rebuild the jar: on first start the bundled `web/` folder is extracted
to `storage.webDir` (default `config/onlinechat/web`) together with a hidden `.exist`
marker, and static files are served **disk-first** with the jar as fallback
(`WebAssets`). Edit the files there; the `.exist` marker is a SHA-256 **manifest**, so on a
later upgrade the mod refreshes only files you never touched, keeps your edits, and
key-merges `locales/*.json`. Delete the marker to re-extract pristine defaults.

For a full replacement, swap `src/main/resources/web/*` for your own build. The API
contract is documented in [WEB_API.md](WEB_API.md) and stable across patch releases.
Keep `index.html` at `/web/index.html` so the fallback route works, and serve
`2fa.html` for `/2fa/auth/<token>` if you keep 2FA enabled.

### Seamless upgrade from an older version

Upgrading is designed to need no manual migration:

* **Config TOML** — NeoForge auto-fills keys missing from an old `onlinechat-*.toml` with
  their new defaults and clamps out-of-range values (e.g. an old `chatHistorySize = 0` is
  corrected to the new default). New sections (`[twoFactor]`, `[limits]`, `language`,
  `certDir`, `webDir`, …) simply appear.
* **TLS paths** — an old config may still carry the pre-`certDir` defaults
  `certChainPath = "./ssl/fullchain.pem"` / `privateKeyPath = "./ssl/privkey.pem"`.
  `SslContexts` treats those exact literals as *unset* (logging an INFO once) so the new
  `certDir + fileName` keys take effect; any other explicit path still wins.
* **Accounts** — `accounts.json` is read with Gson; a record missing the newer
  `twoFactorEnabled` field defaults to `false`. No rewrite needed.
* **Tokens** — `TokenService` still accepts the older three-part
  `username.expiry.signature` form until it expires, deriving `issuedAt` from the expiry and
  the configured TTL, so an upgrade does not force every web user to log in again.
* **Web front-end** — see above; the manifest drives a per-file merge instead of the old
  all-or-nothing `.exist` guard.

### Add or change an in-game string

1. Add the key to **both** `assets/onlinechat/lang/en_us.json` and `zh_cn.json`
   (missing keys fall back to `en_us`, but keep them in sync).
2. Use `Lang.text("onlinechat.your.key", args...)` for a `MutableComponent`, `Lang.tr` for
   a plain `String`, or `Lang.component(key, Component...)` when the arguments are
   themselves styled components. Placeholders follow `String.format` (`%s`).

---

## Debugging tips

* Turn on `verboseLogging = true` in `onlinechat-server.toml` and restart with
  `/onlinechat reload`. Every HTTP request and WebSocket auth is logged with the
  remote address.
* Netty's `IdleStateHandler` is set to 120 s. If your client is behind a proxy
  with a shorter idle timeout, send a `ping` frame from JS every ~60 s.
* The chat history ring is bounded by `storage.chatHistorySize` (minimum `1`). Set it low
  to keep almost nothing in memory, which is handy when debugging memory pressure; older
  messages are still paged from `chatLogFile`.
* `curl -vk https://localhost:8443/api/status` shows the full TLS handshake;
  useful when Netty rejects a certificate format.
* If the mod fails to start with `TLS private key not found`, check `tls.certDir`
  and `tls.keyFileName` (or the `tls.privateKeyPath` override), and remember that
  relative paths resolve against the **working directory** of the JVM, not the
  location of the mod jar.

---

## Building and releasing

```powershell
.\gradlew.bat build              # produces build/libs/onlinechat-1.21.1-neoforge-<version>.jar
.\gradlew.bat publish            # publishes to the local ./repo maven (see build.gradle)
```

Bump `mod_version` in `gradle.properties` before cutting a release. The version
string is substituted into `META-INF/neoforge.mods.toml` by the
`generateModMetadata` task at build time.

Release procedure per version:

1. `git checkout <branch>` (this branch builds 1.21.1).
2. `.\gradlew.bat build` and verify the E2E suite (`%TEMP%\oc-e2e\server-driver.ps1`
   locally — HTTP/HTTPS, WebSocket, REST and RCON checks).
3. Copy `build/libs/onlinechat-1.21.1-neoforge-<version>.jar` into the git-ignored
   `release/` folder.
4. Repeat for `mc/1.21.8` and `mc/1.20.1` (on 1.20.1 copy the **`-all.jar`**).

---

## Testing checklist (manual)

There are no automated tests yet — the surface is small enough that a manual
pass covers everything. Before shipping a change, walk through:

1. Fresh install: register → login → see empty history.
2. Bind to an offline player → clear error, no `[Yes]` sent.
3. Bind to an online player → prompt appears, click **[Yes]**, web UI updates.
4. Bind again with `allowRebind = false` → error.
5. Send a chat from web → appears in game with orange prefix.
6. Send a chat from game → appears on web with green prefix.
7. Die in game → system message on web (if `death` is in `systemMessageKinds`).
8. Kill the WebSocket (close the tab) → `... disconnected from the web chat`
   appears in game (if `bridgeWebPresence = true`).
9. Restart the server → history replays on next connect, tokens still valid.
10. Rotate `token.secret` → old tokens rejected, users must re-login.
11. First start → `config/onlinechat/web/` is extracted with a hidden `.exist` (a SHA-256 manifest); an
    edited `style.css` survives a restart; delete `.exist` and restart → files are overwritten.
11b. Upgrade in place → after editing `style.css`, replace the jar with a newer build and restart: stock
    files refresh, the edited `style.css` is kept (WARN if it also changed upstream), new files appear,
    removed-and-unmodified files are deleted, and `locales/*.json` gain any new keys.
12. `language = "zh_cn"` → bind prompt and `/onlinechat` feedback are Chinese; an unknown
    code falls back to English.
13. `twoFactor.enabled = true`, 2FA switched on from the Account page → on join the player
    cannot move / interact / run commands and a link appears in chat; a browser signed in
    as the bound account → **It's me** releases; a different account → kick; timeout → kick.
14. Change password on the Account page → this tab stays signed in, other devices land on the
    login page with a notice; delete the account → player auto-unbound, all sessions dropped.
