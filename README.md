# Online Chat — Minecraft ⇄ Web bridge (NeoForge 1.21.8)

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
* 🚫 Zero extra runtime dependencies — MC 1.21.8 ships the complete Netty 4.2.7 (including netty-codec-http) and Gson, so nothing is embedded or installed.

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
| `master` | 1.21.1 | 21.1.233+ | `neoforge` | 21 | ModDevGradle 2.0.147 · Gradle 9.2.1 | `onlinechat-1.21.1-neoforge-<ver>.jar` |
| **`mc/1.21.8`** *(this branch)* | 1.21.8 | 26.1.2.109+ | `neoforge` | 25 | ModDevGradle 2.0.147 · Gradle 9.2.1 | `onlinechat-1.21.8-neoforge-<ver>.jar` |
| `mc/1.20.1` | 1.20.1 | 47.1.106+ | `forge` | 17 | NeoGradle 6.0.21 · Gradle 8.1.1 | `onlinechat-1.20.1-neoforge-<ver>-all.jar` |

Branch-specific notes for **1.21.8**:

* MC 1.21.8 bundles the **complete** Netty 4.2.7 — including `netty-codec-http` — so this
  branch embeds nothing and needs no runtime workarounds.
* Mojang renamed "1.21.8" to loader version **26.1.2**, which is why
  `minecraft_version_range=[26.1.2,26.2)` in `gradle.properties`.
* The `mc/1.20.1` build produces an extra `-all.jar` — install the `-all.jar` there.
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
   The jar is written to `build/libs/onlinechat-1.21.8-neoforge-0.0.3-alpha.jar`.
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

`0.0.1-alpha` registered a listener on the **abstract** `PlayerInteractEvent`, which makes the 1.21.1-era
NeoForge builds (21.1.x) abort during `ServerStarting` with:

> `Cannot register listeners for abstract class net.neoforged.neoforge.event.entity.player.PlayerInteractEvent`

`0.0.2-alpha` and newer register the four concrete interaction subclasses instead
(`RightClickBlock` / `RightClickItem` / `EntityInteract` / `LeftClickBlock`), so the 2FA freeze still
blocks every interaction without crashing the server. If you see that error, replace the jar with
`onlinechat-1.21.8-neoforge-0.0.3-alpha.jar` — no config or data migration is needed. These releases also
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
| Zero external runtime dependencies (Netty & Gson come from Minecraft) | ✅ |

---

## Requirements

* Minecraft **1.21.8**
* NeoForge **26.1** or newer
* Java **25**
* A TLS certificate (self-signed is fine for LAN testing, Let's Encrypt for public exposure)

---

## Where things live

```
./ssl/                                    # TLS material (input)
./config/onlinechat-common.toml           # chat bridge settings (created on first run)
./config/onlinechat-server.toml           # HTTPS + auth + storage + 2FA settings (created on first run)
./config/onlinechat/web/                  # editable copy of the web UI + hidden .exist marker (created on first run)
./onlinechat/accounts.json                # web accounts, bindings, 2FA flags (created on first run)
./onlinechat/token.secret                 # auto-generated HMAC secret (created on first run)
./onlinechat/chat_history.jsonl           # append-only chat archive (created on first run)
```

Never commit `./ssl`, `./onlinechat/accounts.json` or `./onlinechat/token.secret` to source control.
A `.gitignore` rule for `ssl/` is already provided by the template.

---

## License

See [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt) for the underlying NeoForge MDK template license.
The Online Chat source in this repository is provided as-is for personal and commercial server use.
