# Online Chat — Minecraft ⇄ Web bridge (NeoForge 1.20.1)

> Languages: **English** | [简体中文](README.zh-CN.md)

A self-contained **HTTPS + WebSocket** chat platform embedded in your Minecraft server.
Web users register, log in, bind their web account to their in-game account with a
clickable **[Yes] / [No]** confirmation, and then chat with players in real time.

* 🔐 TLS served from your own PEM material (default folder `./ssl`, directory configurable) — nothing leaves your machine.
* 🧑‍🤝‍🧑 Two-way bridge: in-game chat appears on the web, web chat appears in-game.
* 🎨 Distinct prefixes: green `[In Game]` on the web, orange `[Web Chat]` in-game.
* 📢 Optional bridging of non-player messages (join, quit, death, advancement).
* 🔍 Full-archive chat **search** from the web UI, with @-mention autocomplete and highlighting.
* 📣 `/onlinechat announce` broadcasts an operator announcement to game *and* web at once.
* ⚙️ Split configuration files (`onlinechat-common.toml` + `onlinechat-server.toml`).
* 🛡️ Optional **2FA on join**: a player with it enabled is frozen until they confirm from a browser that is signed in to their bound web account.
* 🌐 Every in-game line is translated **server-side** (`language = "en_us" | "zh_cn"`) — vanilla clients see it.
* 🧩 The web front-end is extracted to `config/onlinechat/web/` on first start so you can restyle it without rebuilding the jar.
* 🚫 Nothing extra to install — Netty core and Gson come from Minecraft itself; the one Netty module 1.20.1 lacks (`netty-codec-http`) rides inside the `-all.jar` via JarInJar.

---

## Table of contents

| Document | Purpose |
|----------|---------|
| [README.md](README.md) *(this file)* | Overview, feature list, quick start |
| [docs/INSTALL.md](docs/INSTALL.md) | Building the mod, deploying it, TLS setup |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Every option in both config files, explained |
| [docs/WEB_API.md](docs/WEB_API.md) | REST endpoints and WebSocket protocol reference |
| [docs/SECURITY.md](docs/SECURITY.md) | Threat model, hardening checklist, data storage |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Project layout and how to extend the mod |

> 简体中文版文档见 [README.zh-CN.md](README.zh-CN.md) 与 `docs/zh/` 目录
> （[安装](docs/zh/INSTALL.md)、[配置](docs/zh/CONFIGURATION.md)、[Web API](docs/zh/WEB_API.md)、[安全](docs/zh/SECURITY.md)、[开发](docs/zh/DEVELOPMENT.md)）。

---

## Supported versions & repository layout

This mod supports three Minecraft generations, one **branch per version** — a single jar
cannot cover all three (1.20.1 still uses the `net.minecraftforge` namespaces, and the event,
config and component APIs differ between NeoForge 21.1 and 26.1):

| Branch | Minecraft | NeoForge | Loader dep | Build JDK | Toolchain | Jar to install |
|--------|-----------|----------|-----------|-----------|-----------|----------------|
| `master` | 1.21.1 | 21.1.250+ | `neoforge` | 21 | ModDevGradle 2.0.147 · Gradle 9.2.1 | `onlinechat-1.21.1-neoforge-<ver>.jar` |
| `mc/1.21.8` | 1.21.8 | 26.1.2.109+ | `neoforge` | 25 | ModDevGradle 2.0.147 · Gradle 9.2.1 | `onlinechat-1.21.8-neoforge-<ver>.jar` |
| **`mc/1.20.1`** *(this branch)* | 1.20.1 | 47.1.106+ | `forge` | 17 | NeoGradle 6.0.21 · Gradle 8.1.1 | `onlinechat-1.20.1-neoforge-<ver>-all.jar` |

Branch-specific notes for **1.20.1**:

* The build produces **two** jars: the plain `onlinechat-1.20.1-neoforge-0.0.3-alpha.jar` and
  the `-all.jar`. **Install the `-all.jar`** — it embeds `netty-codec-http` 4.1.82 via JarInJar;
  the plain jar is only an intermediate build artifact and will crash with
  `NoClassDefFoundError: HttpServerCodec` at runtime.
* MC 1.20.1 bundles Netty 4.1.82 **without** `netty-codec-http` (that is why the `-all.jar`
  carries it). Netty core and Gson still come from Minecraft itself.
* On 1.20.1 the **server** config lives next to the world: `world/serverconfig/onlinechat-server.toml`
  on a dedicated server (`saves/<world>/serverconfig/` on a client). The **common** config is
  still `config/onlinechat-common.toml`. (On 1.21.1 / 1.21.8 the server config moved to `config/`.)
* Building requires a **JDK 17** daemon (NeoGradle 6 cannot run on JDK 20+); see `docs/INSTALL.md`.
* Release jars for all three versions are kept in the local `release/` folder (git-ignored):
  `git checkout <branch>` then `.\gradlew.bat build`, and copy the jar over.

---

## Quick start (5 minutes)

1. **Drop your TLS material** into `./ssl/`:
   ```
   ssl/
   ├── fullchain.pem   # certificate + intermediates
   ├── privkey.pem     # PKCS#8 (or PKCS#1) private key, may be password-protected
   └── cert.pem        # optional, unused by default
   ```
2. **Build the mod**:
   ```powershell
   .\gradlew.bat build
   ```
   Two jars are produced; install the **`-all.jar`**:
   `build/libs/onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar`
   (it carries the embedded `netty-codec-http` — see the version table above).
3. **Install** it into your `mods/` folder (server and/or client — the web server only
   starts on the logical server side).
4. **Start Minecraft** (dedicated server or single-player world opened to LAN — both work).
   The web server listens on `https://0.0.0.0:8443/` by default.
5. **Open** `https://<your-host>:8443/` in a browser, register, log in.
6. **Bind**: open **Account** and type your Minecraft username in the *Bind Minecraft player* panel.
   In game you will see:
   > **[OnlineChat]** Web user *alice* wants to bind to your Minecraft account. *(Code: X7K2QM)*
   > **[Yes] [No]**
   
   Click **[Yes]**. That's it — you can now talk to the server from your browser.
7. *(Optional)* Set `twoFactor.enabled = true` and `twoFactor.publicUrl` in `onlinechat-server.toml`;
   players can then flip the **Two-factor** switch on their Account page.

---

## What you get in game

| Situation | In-game chat line |
|-----------|-------------------|
| Web user *alice* (bound to *Steve*) says "hi" | <span style="color:#e67e22">**[Web Chat]**</span> `<Steve> hi` |
| Player *Steve* says "hello" | Vanilla `<Steve> hello` |
| Web user *bob* connects to the WebSocket | `[OnlineChat] bob connected to the web chat` |
| Binding confirmed | `[OnlineChat] Web user 'alice' bound to Steve.` |
| *Steve* joins with 2FA on | `[OnlineChat] Two-factor login is enabled on your account. Open this link …` + clickable `https://…/2fa/auth/…` (frozen until confirmed) |

The prefix text, colour and the whole line format are configurable — see
[docs/CONFIGURATION.md](docs/CONFIGURATION.md#prefixes).

## What you get on the web

| Situation | Web view |
|-----------|----------|
| Player *Steve* says "hello" | <span style="color:#2ecc71">**[In Game]**</span> **Steve** hello |
| Another web user *bob* says "yo" | <span style="color:#e67e22">**[Web Chat]**</span> **bob** yo |
| *Steve* died | `Steve fell from a high place` (system, italic grey) |
| *Steve* joined the game | `Steve joined the game` (system) |

---

## v0.0.2+ — fixes the server-start crash in 0.0.1

`0.0.1-alpha` registered a listener on the **abstract** `PlayerInteractEvent`, which makes NeoForge
(21.1.233+) abort during `ServerStarting` with:

> `Cannot register listeners for abstract class net.neoforged.neoforge.event.entity.player.PlayerInteractEvent`

`0.0.2-alpha` and newer register the four concrete interaction subclasses instead
(`RightClickBlock` / `RightClickItem` / `EntityInteract` / `LeftClickBlock`), so the 2FA freeze still
blocks every interaction without crashing the server. If you see that error, replace the jar with
`onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar` — no config or data migration is needed. These releases also
add archive search, announcements and the web-chat UX improvements listed above.

---

## Feature checklist

| Feature | Status |
|---------|--------|
| Web login / register (PBKDF2-hashed) | ✅ |
| HMAC-signed stateless tokens | ✅ |
| Cookie-based sessions (`oc_token`, HttpOnly/Secure/SameSite) | ✅ |
| Bind web account ⇄ Minecraft player with clickable **[Yes]** confirmation | ✅ |
| Binding requires the MC player to be **online** | ✅ |
| HTTPS from local `./ssl` PEM material — never leaves the host | ✅ |
| Two-way chat bridge | ✅ |
| Orange `[Web Chat]` prefix in game for web senders | ✅ |
| Green `[In Game]` prefix on web for game senders | ✅ |
| Configurable bridging of non-player messages (join/quit/death/advancement) | ✅ |
| Split configuration (`common` + `server` TOML files) | ✅ |
| Separate login / account / chat pages | ✅ |
| Account page: bind, change password, delete account (auto-unbinds) | ✅ |
| Account page: joined / last-sign-in info, one-click UUID copy | ✅ |
| Optional per-player **2FA on join** (browser confirmation, timeout kick) | ✅ |
| Operator commands `/onlinechat account setpassword\|delete` | ✅ |
| `/onlinechat announce <text>` — announcement to game + web | ✅ |
| `/onlinechat webusers` — list web users with a live session | ✅ |
| Bilingual UI + **server-side** in-game translation (English & 简体中文) | ✅ |
| Web front-end extracted to `config/onlinechat/web/` for customisation | ✅ |
| Chat history replay on WebSocket connect + paged archive | ✅ |
| Full-archive chat search (`GET /api/search`) + search overlay in the chat page | ✅ |
| Web chat UX: date separators, message grouping, clickable links, @mention highlight & autocomplete, copy button, draft restore, unread title badge, optional sound, password visibility toggles | ✅ |
| WebSocket shared across pages (SharedWorker) + smooth in-app page transitions — navigating Chat ⇄ Account never drops the socket or spams connect/disconnect messages | ✅ |
| Login rate limiting per IP | ✅ |
| CORS allow-list | ✅ |
| Zero external runtime dependencies (Netty core & Gson come from Minecraft; netty-codec-http rides inside the `-all.jar`) | ✅ |

---

## Requirements

* Minecraft **1.20.1**
* NeoForge **47.1.106** or newer
* Java **17** (the runtime Minecraft 1.20.1 ships to players; the build also needs a JDK 17 daemon — see [docs/INSTALL.md](docs/INSTALL.md))
* A TLS certificate (self-signed is fine for LAN testing, Let's Encrypt for public exposure)

---

## Where things live

```
./ssl/                                       # TLS material (input)
./config/onlinechat-common.toml              # chat bridge settings (created on first run)
./world/serverconfig/onlinechat-server.toml  # HTTPS + auth + storage + 2FA (per-world on 1.20.1, created on first run)
./config/onlinechat/web/                     # editable copy of the web UI + hidden .exist marker (created on first run)
./onlinechat/accounts.json                   # web accounts, bindings, 2FA flags (created on first run)
./onlinechat/token.secret                    # auto-generated HMAC secret (created on first run)
./onlinechat/chat_history.jsonl              # append-only chat archive (created on first run)
```

Never commit `./ssl`, `./onlinechat/accounts.json` or `./onlinechat/token.secret` to source control.
A `.gitignore` rule for `ssl/` is already provided by the template.

---

## License

See [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt) for the underlying NeoForge MDK template license.
The Online Chat source in this repository is provided as-is for personal and commercial server use.
