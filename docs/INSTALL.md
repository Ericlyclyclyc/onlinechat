# Installation

This document covers building the mod from source, deploying it to a server, and
preparing the TLS material it needs.

---

## 1. Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK | 21 (Microsoft OpenJDK, Temurin, Adoptium all work) |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.250 or newer |
| Gradle | Provided by the wrapper — no system install needed |

Windows PowerShell, macOS Terminal and Linux bash are all supported.

---

## 2. Build the mod jar

```powershell
.\gradlew.bat build
```

The output jar is written to:

```
build/libs/onlinechat-1.0.0.jar
```

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
./ssl/privkey.pem     # private key (PKCS#8 or PKCS#1, may be password-protected)
```

Relative paths are resolved against the **working directory** of the Minecraft server
(the folder that contains `server.properties`).

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

1. Drop `onlinechat-1.0.0.jar` into your server's `mods/` folder.
2. Make sure `./ssl/fullchain.pem` and `./ssl/privkey.pem` exist relative to the
   server's working directory.
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

For development you can point `certChainPath` and `privateKeyPath` at the project
root with an absolute path or `../ssl/...`.

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
