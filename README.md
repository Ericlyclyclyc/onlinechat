# Online Chat — Minecraft ⇄ Web bridge (NeoForge 1.21.1)

A self-contained **HTTPS + WebSocket** chat platform embedded in your Minecraft server.
Web users register, log in, bind their web account to their in-game account with a
clickable **[Yes] / [No]** confirmation, and then chat with players in real time.

* 🔐 TLS served from your own PEM material in `./ssl` — nothing leaves your machine.
* 🧑‍🤝‍🧑 Two-way bridge: in-game chat appears on the web, web chat appears in-game.
* 🎨 Distinct prefixes: green `[In Game]` on the web, orange `[Web Chat]` in-game.
* 📢 Optional bridging of non-player messages (join, quit, death, advancement).
* ⚙️ Split configuration files (`onlinechat-common.toml` + `onlinechat-server.toml`).
* 🚫 Zero extra runtime dependencies — Netty and Gson are supplied by Minecraft itself.

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
   The jar is written to `build/libs/onlinechat-1.0.0.jar`.
3. **Install** it into your `mods/` folder (server and/or client — the web server only
   starts on the logical server side).
4. **Start Minecraft** (dedicated server or single-player world opened to LAN — both work).
   The web server listens on `https://0.0.0.0:8443/` by default.
5. **Open** `https://<your-host>:8443/` in a browser, register, log in.
6. **Bind**: type your Minecraft username in the *Bind Minecraft player* panel.
   In game you will see:
   > **[OnlineChat]** Web user *alice* wants to bind to your Minecraft account. *(Code: X7K2QM)*
   > **[Yes] [No]**
   
   Click **[Yes]**. That's it — you can now talk to the server from your browser.

---

## What you get in game

| Situation | In-game chat line |
|-----------|-------------------|
| Web user *alice* (bound to *Steve*) says "hi" | <span style="color:#e67e22">**[Web Chat]**</span> `<Steve> hi` |
| Player *Steve* says "hello" | Vanilla `<Steve> hello` |
| Web user *bob* connects to the WebSocket | `[OnlineChat] bob connected to the web chat` |
| Binding confirmed | `[OnlineChat] Web user 'alice' bound to Steve.` |

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
| Separate login / bind / chat pages | ✅ |
| Bilingual UI + lang files (English & 简体中文) | ✅ |
| Chat history replay on WebSocket connect | ✅ |
| Login rate limiting per IP | ✅ |
| CORS allow-list | ✅ |
| Zero external runtime dependencies (Netty & Gson come from Minecraft) | ✅ |

---

## Requirements

* Minecraft **1.21.1**
* NeoForge **21.1.250** or newer
* Java **21**
* A TLS certificate (self-signed is fine for LAN testing, Let's Encrypt for public exposure)

---

## Where things live

```
./ssl/                                    # TLS material (input)
./config/onlinechat-common.toml           # chat bridge settings (created on first run)
./config/onlinechat-server.toml           # HTTPS + auth + storage settings (created on first run)
./onlinechat/accounts.json                # web accounts, bindings (created on first run)
./onlinechat/token.secret                 # auto-generated HMAC secret (created on first run)
```

Never commit `./ssl`, `./onlinechat/accounts.json` or `./onlinechat/token.secret` to source control.
A `.gitignore` rule for `ssl/` is already provided by the template.

---

## License

See [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt) for the underlying NeoForge MDK template license.
The Online Chat source in this repository is provided as-is for personal and commercial server use.
