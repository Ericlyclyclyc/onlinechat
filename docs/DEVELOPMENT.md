# Development guide

A tour of the source tree, the runtime wiring, and the extension points you are
most likely to touch.

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
├── bridge/
│   ├── BindingManager.java       # Pending bind codes, TTL, confirm/deny
│   ├── ChatBridge.java           # Event listeners + broadcast helpers + history
│   └── WebSessionManager.java    # Live WebSocket sessions keyed by channel and username
├── command/
│   └── OnlineChatCommand.java    # /onlinechat … command tree + clickable [Yes]/[No]
├── config/
│   ├── CommonConfig.java         # onlinechat-common.toml
│   └── ServerConfig.java         # onlinechat-server.toml
└── web/
    ├── HttpApiHandler.java       # REST + static + WebSocket handshake
    ├── SslContexts.java          # PEM → Netty SslContext
    ├── WebServer.java            # Netty bootstrap
    └── WebSocketFrameHandler.java# JSON frame protocol

src/main/resources/
├── assets/onlinechat/lang/en_us.json
└── web/
    ├── index.html                # SPA served at /
    ├── style.css
    └── app.js

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
      │  new AccountManager(...).load()
      │  new TokenService(...).init()      ← generates token.secret on first run
      │  new WebSessionManager()
      │  new ChatBridge(sessions, accounts).setServer(mc)
      │  new BindingManager(accounts, bridge)
      │  NeoForge.EVENT_BUS.register(bridge)
      │  new WebServer(...)                ← constructed, not started yet
      ▼
ServerStartedEvent
      │
      ▼
WebServer.start()
      │  SslContexts.buildServerContext(runDir)   ← reads ./ssl/*.pem
      │  Netty pipeline:
      │      SslHandler → IdleStateHandler → HttpServerCodec →
      │      HttpObjectAggregator → ChunkedWriteHandler →
      │      HttpApiHandler → WebSocketFrameHandler
      ▼
Ready. Clients connect to https://host:port/
```

On `ServerStoppingEvent` the pipeline is reversed: the Netty channels are closed,
event loop groups shut down gracefully, the `ChatBridge` is unregistered from the
event bus, and `AccountManager.save()` flushes any pending mutations.

---

## Key design decisions

### Why Netty (and not `com.sun.net.httpserver` or a third-party lib)?

Minecraft already bundles Netty 4.1.97.Final (`netty-codec-http`, `netty-handler`,
`netty-transport`, …). Using Netty means:

* **Zero extra runtime dependencies** — nothing to shade, no jar-in-jar, no
  version conflicts with other mods.
* Native support for the WebSocket protocol (`WebSocketServerHandshaker`,
  `TextWebSocketFrame`) and TLS (`SslContextBuilder.forServer(File, File)`).
* Battle-tested event-loop model, matching Minecraft's own network layer.

The compile classpath needs an explicit `compileOnly` on the Netty artefacts
(see `build.gradle`) because ModDevGradle does not re-export Minecraft's
transitive dependencies. At runtime the classes come from Minecraft itself.

### Why stateless HMAC tokens and not sessions?

* No server-side state to lose on restart.
* Trivial to scale out (though this mod is single-server by nature).
* Rotating the secret invalidates every token in one step.

The trade-off is that we cannot revoke a single token before its expiry — if you
need that, delete the account or shorten `auth.tokenTtlMinutes`.

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

---

## Extending the mod

### Add a new bridged system event

1. Add a kind string to the allow-list in `CommonConfig.SYSTEM_MESSAGE_KINDS`.
2. Add a `@SubscribeEvent` handler in `ChatBridge`, gated on
   `shouldBridge("<yourKind>")`.
3. Emit with `rememberAndBroadcast(new ChatMessage(..., Kind.SYSTEM, ..., "<yourKind>"))`.
4. Optionally teach the web UI to render it specially (see `appendMessage` in
   `web/app.js`).

### Add a new REST endpoint

1. Add a case in `HttpApiHandler.handleApi`'s switch.
2. Implement a `handleXxx(ChannelHandlerContext, FullHttpRequest)` method.
3. Use `auth(req)` to require a token, or skip it for public endpoints.
4. Reply with `sendJson(ctx, req, HttpResponseStatus.OK, jsonObject)`.

### Add a new WebSocket message type

1. Extend the switch in `WebSocketFrameHandler.channelRead0`.
2. Update `docs/WEB_API.md` with the new frame shape.
3. Handle the new type in `web/app.js`'s `handleWsMessage`.

### Swap the storage backend

`AccountManager` is the only place that touches disk. Replace its `load()`/`save()`
with SQLite, MariaDB or an HTTP call — the rest of the codebase uses the in-memory
`Account` POJOs and never sees the storage layer.

### Custom web UI

Replace `src/main/resources/web/*` with your own build. The API contract is
documented in [WEB_API.md](WEB_API.md) and stable across patch releases. Keep
`index.html` at `/web/index.html` so the fallback route works.

---

## Debugging tips

* Turn on `verboseLogging = true` in `onlinechat-server.toml` and restart with
  `/onlinechat reload`. Every HTTP request and WebSocket auth is logged with the
  remote address.
* Netty's `IdleStateHandler` is set to 120 s. If your client is behind a proxy
  with a shorter idle timeout, send a `ping` frame from JS every ~60 s.
* The chat history ring is bounded by `storage.chatHistorySize`. Setting it to
  `0` disables replay entirely, which is handy when debugging memory pressure.
* `curl -vk https://localhost:8443/api/status` shows the full TLS handshake;
  useful when Netty rejects a certificate format.
* If the mod fails to start with `TLS private key not found`, remember that
  relative paths resolve against the **working directory** of the JVM, not the
  location of the mod jar.

---

## Building and releasing

```powershell
.\gradlew.bat build              # produces build/libs/onlinechat-<version>.jar
.\gradlew.bat publish            # publishes to the local ./repo maven (see build.gradle)
```

Bump `mod_version` in `gradle.properties` before cutting a release. The version
string is substituted into `META-INF/neoforge.mods.toml` by the
`generateModMetadata` task at build time.

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
