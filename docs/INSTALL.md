# Installation

> Languages: **English** | [简体中文](zh/INSTALL.md)

This document covers building the mod from source, deploying it to a server, and
preparing the TLS material it needs. It is written for the **1.20.1** branch
(`mc/1.20.1`); other Minecraft versions live on their own branches — see the
version table in [README.md](../README.md).

---

## 1. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK | 17 (Microsoft OpenJDK, Temurin, Adoptium all work) |
| Minecraft | 1.20.1 |
| NeoForge | 47.1.106 or newer |
| Gradle | Provided by the wrapper — no system install needed |

> **JDK 17 for building is mandatory.** NeoGradle 6 (the build system of this branch)
> cannot run its Gradle daemon on JDK 20+, and the mod targets the Java 17 runtime that
> Minecraft 1.20.1 ships to players. Run the build with a JDK 17 on `PATH`/`JAVA_HOME`,
> or point Gradle at one explicitly (PowerShell):
>
> ```powershell
> $env:JAVA_HOME = 'C:\path\to\jdk-17'
> .\gradlew.bat build
> ```
>
> (The wrapper downloads Gradle 8.1.1 itself — no system Gradle is needed.)

Windows PowerShell, macOS Terminal and Linux bash are all supported.

---

## 2. Build the mod jar

```powershell
.\gradlew.bat build
```

The build produces **two** jars:

```
build/libs/onlinechat-1.20.1-neoforge-0.0.3-alpha.jar      # intermediate — DO NOT install
build/libs/onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar  # the release jar — install this one
```

> **Install the `-all.jar`.** NeoGradle 6 writes the JarInJar (embedded `netty-codec-http`)
> into the `-all.jar`; the plain jar has no embedded dependency and the web server crashes with
> `NoClassDefFoundError: HttpServerCodec` as soon as the first HTTP request arrives.
>
> The file name follows the NeoForge convention `<modid>-<mcversion>-<loader>-<modversion>.jar`.
> It is derived from `mod_id`, `minecraft_version` and `mod_version` in `gradle.properties`, so it
> tracks your version automatically.

If Gradle reports missing dependencies after a network change, refresh them with:

```powershell
.\gradlew.bat --refresh-dependencies build
```

The `-all.jar` contains:
* All compiled mod classes (re-obfuscated to SRG names for the production runtime)
* The web frontend under `web/` (served by the embedded HTTPS server)
* `META-INF/mods.toml`
* Language file `assets/onlinechat/lang/en_us.json`
* The embedded `META-INF/jarjar/netty-codec-http-4.1.82.Final.jar` + JarInJar metadata

Netty core (buffer/transport/handler/codec) and Gson come from Minecraft itself; the mod
only ships the one Netty module 1.20.1 lacks (`netty-codec-http`). No other runtime
dependencies are bundled or required.

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

1. Drop `onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar` into your server's `mods/` folder
   (the **`-all.jar`**, see §2 — the plain jar has no embedded dependency).
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
stops when you leave it. Note that on 1.20.1 the **server config is always per-world**:

* The world's `onlinechat-server.toml` is created inside
  `saves/<world>/serverconfig/` — and on a **dedicated** server it lives at
  `world/serverconfig/onlinechat-server.toml` (next to the world folder, not in `config/`).
* The `onlinechat-common.toml` is global (`config/`).
* `./ssl` still resolves against the Minecraft run directory (`run/` in a dev
  workspace, or the launcher's instance folder in production).

For development you can set `tls.certDir` to `../ssl` (or an absolute path) so it points at the
project root, or override `tls.certChainPath` / `tls.privateKeyPath` directly.

---

## 6. Development workspace

This branch uses **NeoGradle 6** (not ModDevGradle). The standard run configs still exist:

```powershell
.\gradlew.bat runServer    # dedicated server, useful for testing the web UI
.\gradlew.bat runClient    # client + integrated server
.\gradlew.bat runData      # data generation (unused by this mod)
```

* **JDK 17 daemon required** (see §1) — NeoGradle 6 cannot run on JDK 20+.
* Gradle is pinned to **8.1.1** by the wrapper (NeoGradle 6 does not support Gradle 9).
* Mappings are **parchment** `2023.09.03-1.20.1` (plain `official` is broken in NeoGradle 6).
* The dev server loads `netty-codec-http` through a `build.gradle` workaround
  (`afterEvaluate` block appending it to the run tasks' minecraft artifacts), because
  FML 1.20.1 only indexes the legacy classpath file, not the launcher `-cp`.
* The source mods.toml lives at `src/main/resources/META-INF/mods.toml`
  (NeoGradle convention, values are literals — no `generateModMetadata` templating).

The dev working directory is `run/`, so copy or symlink the `ssl/` folder there:

```powershell
New-Item -ItemType Junction -Path .\run\ssl -Target ..\ssl
```

Or edit `run/world/serverconfig/onlinechat-server.toml` (per-world on 1.20.1) and set
absolute paths, e.g. `tls.certDir = "../ssl"` for the project root.

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
  simply appear. On 1.20.1 the server config lives at `world/serverconfig/onlinechat-server.toml`
  (dedicated) or `saves/<world>/serverconfig/` (single-player).
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
