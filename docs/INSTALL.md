# Installation

> Languages: **English** | [简体中文](zh/INSTALL.md)

This document covers building the mod from source, deploying it to a server, and
preparing the TLS material it needs.

---

## 1. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK | 25 (Microsoft OpenJDK, Temurin, Adoptium all work) |
| Minecraft | 1.21.8 |
| NeoForge | 26.1 or newer |
| Gradle | Provided by the wrapper — no system install needed |

Windows PowerShell, macOS Terminal and Linux bash are all supported.

---

## 2. Build the mod jar

```powershell
.\gradlew.bat build
```

The output jar is written to:

```
build/libs/onlinechat-1.21.8-neoforge-0.0.3-alpha.jar
```

> The file name follows the NeoForge convention `<modid>-<mcversion>-<loader>-<modversion>.jar`.
> It is derived from `mod_id`, `minecraft_version` and `mod_version` in `gradle.properties`, so it
> tracks your version automatically.

If Gradle reports missing dependencies after a network change, refresh them with:

```powershell
.\gradlew.bat --refresh-dependencies build
```

The jar contains:
* All compiled mod classes
* The web frontend under `web/` (served by the embedded HTTPS server)
* `META-INF/neoforge.mods.toml`
* Language file `assets/onlinechat/lang/en_us.json`

**No external runtime dependencies are bundled.** Netty and Gson are provided by
Minecraft itself, and the mod only references them at compile time.

---

## 3. Prepare the TLS material

The mod serves HTTPS using PEM files. By default it reads:

```
./ssl/fullchain.pem   # certificate + intermediate chain
./ssl/privkey.pem     # private key, unencrypted PKCS#8 (or encrypted PKCS#8 + privateKeyPassword)
```

Relative paths are resolved against the **working directory** of the Minecraft server
(the folder that contains `server.properties`).

> **The key must be PKCS#8.** The embedded web server reads keys through the JDK TLS provider,
> which only understands `-----BEGIN PRIVATE KEY-----` (PKCS#8) — traditional SEC1
> (`-----BEGIN EC PRIVATE KEY-----`) and PKCS#1 (`-----BEGIN RSA PRIVATE KEY-----`) keys are
> detected at start-up and refused with an actionable guide instead of a cryptic error.
> Convert such a key once with:
>
> ```bash
> openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey-pkcs8.pem
> ```
>
> (drop `-nocrypt` and set `tls.privateKeyPassword` instead if you prefer an encrypted PKCS#8 key).

> **The directory is configurable.** Set `tls.certDir` (default `./ssl`) to point at any folder —
> for example your Let's Encrypt `live/<domain>/` directory — and the mod will read
> `<certDir>/fullchain.pem` and `<certDir>/privkey.pem`. Use `tls.certFileName` / `tls.keyFileName`
> if your files are named differently, or `tls.certChainPath` / `tls.privateKeyPath` for a full
> explicit path that overrides the directory entirely.

### Option A — Let's Encrypt (recommended for public servers)

Use [certbot](https://certbot.eff.org/) or [acme.sh](https://github.com/acmesh-official/acme.sh)
to issue a certificate for your domain, then copy or symlink the files into `./ssl/`:

```bash
sudo cp /etc/letsencrypt/live/chat.example.com/fullchain.pem ./ssl/
sudo cp /etc/letsencrypt/live/chat.example.com/privkey.pem   ./ssl/
sudo chown minecraft:minecraft ./ssl/*.pem
sudo chmod 600 ./ssl/privkey.pem
```

`privkey.pem` from Let's Encrypt is PKCS#8 and unencrypted — the mod reads it directly.

### Option B — Self-signed (LAN / testing)

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
    -keyout ./ssl/privkey.pem \
    -out    ./ssl/fullchain.pem \
    -subj   "/CN=minecraft.local"
```

Browsers will warn about the self-signed certificate. Add a permanent exception or
install the certificate into your OS trust store.

### Option C — Password-protected key

If your key is encrypted, put the passphrase in `config/onlinechat-server.toml`:

```toml
[tls]
    privateKeyPassword = "your-passphrase"
```

> ⚠️ **Never commit `./ssl` to source control.** The template already ships with a
> `.gitignore` that excludes the folder.

---

## 4. Install on a dedicated server

1. Drop `onlinechat-1.21.8-neoforge-0.0.3-alpha.jar` into your server's `mods/` folder.
2. Make sure the TLS material exists relative to the server's working directory — by default
   `./ssl/fullchain.pem` and `./ssl/privkey.pem` (or set `tls.certDir` to wherever they live).
3. Start the server as usual (`java -jar ...` or your start script).
4. Watch the log for:
   ```
   [OnlineChat] TLS material loaded: cert=..., key=...
   [OnlineChat] HTTPS/WebSocket server listening on https://0.0.0.0:8443/
   ```
5. Open the port in your firewall:
   * **Windows**: `New-NetFirewallRule -DisplayName "OnlineChat" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow`
   * **Linux (ufw)**: `sudo ufw allow 8443/tcp`

The mod does **not** need to be installed on players' clients. In-game chat and the
`[Yes]` / `[No]` confirmation buttons work with vanilla clients.

---

## 5. Install for single-player / LAN

The same jar works in single-player. The web server starts when you open a world and
stops when you leave it. Note that:

* The world's `onlinechat-server.toml` is created inside
  `saves/<world>/serverconfig/`.
* The `onlinechat-common.toml` is global (`config/`).
* `./ssl` still resolves against the Minecraft run directory (`run/` in a dev
  workspace, or the launcher's instance folder in production).

For development you can set `tls.certDir` to `../ssl` (or an absolute path) so it points at the
project root, or override `tls.certChainPath` / `tls.privateKeyPath` directly.

---

## 6. Development workspace

The template ships with the standard ModDevGradle run configs:

```powershell
.\gradlew.bat runServer    # dedicated server, useful for testing the web UI
.\gradlew.bat runClient    # client + integrated server
.\gradlew.bat runData      # data generation (unused by this mod)
```

The dev working directory is `run/`, so copy or symlink the `ssl/` folder there:

```powershell
New-Item -ItemType Junction -Path .\run\ssl -Target ..\ssl
```

Or edit `run/config/onlinechat-server.toml` and set absolute paths.

---

## 7. Verify the deployment

* **Health check**:
  ```
  curl -k https://localhost:8443/api/status
  ```
  Expected response:
  ```json
  {"ok":true,"service":"onlinechat","registration":true,"onlineWeb":0,"onlinePlayers":0,"maxPlayers":20,"motd":"...","style":{...}}
  ```
* **Web UI**: open `https://<host>:8443/` in a browser, register an account, log in.
* **Binding**: type your Minecraft username in the *Bind Minecraft player* panel
  while you're online in game, then click **[Yes]** on the chat prompt.
* **Bridge**: type a message on the web; it should appear in game prefixed with the
  orange `[Web Chat]` tag. Type in game; it appears on the web with the green
  `[In Game]` tag.

If anything fails, check the server log for `[OnlineChat]` entries and consult
[docs/SECURITY.md](SECURITY.md) and [docs/CONFIGURATION.md](CONFIGURATION.md).

---

## 8. Upgrading from an older version

Drop the new jar over the old one and restart — no manual migration is required. What happens
to your existing data:

* **Config (`onlinechat-common.toml` / `onlinechat-server.toml`)** — NeoForge adds every key the new
  version introduces with its default and keeps your existing values. A value that is now out of range
  is clamped: an old `chatHistorySize = 0` (allowed before, now minimum `1`) becomes the new default
  `300`. New sections such as `[twoFactor]`, `[limits]` and keys like `language`, `certDir`, `webDir`
  simply appear.
* **TLS paths** — if your config still carries the old defaults `certChainPath = "./ssl/fullchain.pem"`
  / `privateKeyPath = "./ssl/privkey.pem"` (from before `certDir` existed), they are treated as unset so
  `certDir + certFileName/keyFileName` take over; the server logs one INFO line suggesting you clear them.
  Any *other* explicit path still wins, so custom setups are untouched.
* **Accounts (`accounts.json`)** — read as-is; records without the newer `twoFactorEnabled` field default
  to two-factor off. Bindings, passwords and salts are preserved.
* **Web sessions** — the older three-part login token (`username.expiry.signature`) stays valid until it
  expires, so upgrading does **not** sign everybody out. New logins get the current four-part token.
* **Web front-end (`config/onlinechat/web`)** — upgraded in place using the hidden `.exist` manifest:
  files you never edited are refreshed, files the new version adds are copied in, files it no longer ships
  are removed (only if unmodified), and **your edited files are kept**. `locales/*.json` are key-merged so
  custom wording survives while new keys still appear. If a file you edited also changed upstream, the log
  prints a WARN listing it so you can merge by hand.
* **Forcing a clean reset** — delete `config/onlinechat/web/.exist` (or the whole `web` directory) and
  restart to re-extract pristine defaults, discarding front-end edits. Config, accounts and chat history
  are never touched by this.

Always keep a backup of `config/onlinechat*` and your `accounts.json` / chat log before upgrading.
