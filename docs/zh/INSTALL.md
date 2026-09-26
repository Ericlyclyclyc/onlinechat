# 安装

> 语言：[English](../INSTALL.md) | **简体中文**

本文档介绍如何从源码构建模组、把它部署到服务器，以及准备它所需的 TLS 材料。
本文针对 **1.20.1** 分支（`mc/1.20.1`）编写；其他 Minecraft 版本在各自的分支上，
见 [README.zh-CN.md](../README.zh-CN.md) 中的版本表。

---

## 1. 前置条件

| 依赖 | 版本 |
|------|------|
| JDK | 17（Microsoft OpenJDK、Temurin、Adoptium 均可） |
| Minecraft | 1.20.1 |
| NeoForge | 47.1.106 或更新 |
| Gradle | 由 wrapper 提供 —— 无需在系统中单独安装 |

> **构建必须使用 JDK 17。** 本分支的构建系统 NeoGradle 6 无法在 JDK 20+ 上运行其
> Gradle 守护进程，且模组面向 Minecraft 1.20.1 提供给玩家的 Java 17 运行时。
> 让 JDK 17 出现在 `PATH`/`JAVA_HOME` 上再构建，或显式指定（PowerShell）：
>
> ```powershell
> $env:JAVA_HOME = 'C:\path\to\jdk-17'
> .\gradlew.bat build
> ```
>
> （wrapper 会自行下载 Gradle 8.1.1 —— 不需要在系统中安装 Gradle。）

Windows PowerShell、macOS Terminal 与 Linux bash 均受支持。

---

## 2. 构建模组 jar

```powershell
.\gradlew.bat build
```

构建会产出 **两个** jar：

```
build/libs/onlinechat-1.20.1-neoforge-0.0.3-alpha.jar      # 中间产物 —— 不要安装
build/libs/onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar  # 发布用 jar —— 安装这个
```

> **请安装 `-all.jar`。** NeoGradle 6 会把 JarInJar（内置的 `netty-codec-http`）写进
> `-all.jar`；普通 jar 没有内置依赖，第一个 HTTP 请求到达时网页服务器就会以
> `NoClassDefFoundError: HttpServerCodec` 崩溃。
>
> 文件名遵循 NeoForge 约定 `<modid>-<mcversion>-<loader>-<modversion>.jar`。
> 它由 `gradle.properties` 中的 `mod_id`、`minecraft_version` 与 `mod_version` 派生，因此会
> 自动跟随你的版本号。

如果网络变动后 Gradle 报告依赖缺失，用以下命令刷新：

```powershell
.\gradlew.bat --refresh-dependencies build
```

`-all.jar` 中包含：
* 全部编译后的模组类（已重混淆为生产运行时的 SRG 名称）
* `web/` 下的网页前端（由内置 HTTPS 服务器提供）
* `META-INF/mods.toml`
* 语言文件 `assets/onlinechat/lang/en_us.json`
* 内置的 `META-INF/jarjar/netty-codec-http-4.1.82.Final.jar` 与 JarInJar 元数据

Netty 核心（buffer/transport/handler/codec）与 Gson 来自 Minecraft 自身；模组只附带
1.20.1 缺的那一个 Netty 模块（`netty-codec-http`），不打包也不要求任何其他运行时依赖。

---

## 3. 准备 TLS 材料

模组使用 PEM 文件提供 HTTPS。默认情况下它读取：

```
./ssl/fullchain.pem   # 证书 + 中间证书链
./ssl/privkey.pem     # 私钥，未加密 PKCS#8（或加密 PKCS#8 + privateKeyPassword）
```

相对路径以 Minecraft 服务器的 **工作目录**（即包含 `server.properties` 的文件夹）为基准解析。

> **私钥必须是 PKCS#8 格式。** 内置网页服务器通过 JDK TLS 提供程序读取私钥，它只认识
> `-----BEGIN PRIVATE KEY-----`（PKCS#8）—— 传统的 SEC1（`-----BEGIN EC PRIVATE KEY-----`）与
> PKCS#1（`-----BEGIN RSA PRIVATE KEY-----`）密钥会在启动时被检测并拒绝，同时打印可操作的
> 修复指引，而不是晦涩的报错。可以一次性转换：
>
> ```bash
> openssl pkcs8 -topk8 -nocrypt -in privkey.pem -out privkey-pkcs8.pem
> ```
>
> （如果更想使用加密的 PKCS#8 密钥，去掉 `-nocrypt` 并在配置中设置 `tls.privateKeyPassword`。）

> **目录可配置。** 设置 `tls.certDir`（默认 `./ssl`）指向任意文件夹 —— 例如你的 Let's Encrypt
> `live/<domain>/` 目录 —— 模组就会读取 `<certDir>/fullchain.pem` 与 `<certDir>/privkey.pem`。
> 若你的文件命名不同，用 `tls.certFileName` / `tls.keyFileName`；若想完全绕过目录、给出完整
> 的显式路径，则用 `tls.certChainPath` / `tls.privateKeyPath` 覆盖。

### 方式 A —— Let's Encrypt（推荐用于公开服务器）

用 [certbot](https://certbot.eff.org/) 或 [acme.sh](https://github.com/acmesh-official/acme.sh)
为你的域名签发证书，然后把文件复制或软链接到 `./ssl/`：

```bash
sudo cp /etc/letsencrypt/live/chat.example.com/fullchain.pem ./ssl/
sudo cp /etc/letsencrypt/live/chat.example.com/privkey.pem   ./ssl/
sudo chown minecraft:minecraft ./ssl/*.pem
sudo chmod 600 ./ssl/privkey.pem
```

Let's Encrypt 的 `privkey.pem` 是未加密的 PKCS#8 —— 模组可直接读取。

### 方式 B —— 自签名（局域网 / 测试）

```bash
openssl req -x509 -newkey rsa:2048 -nodes -days 365 \
    -keyout ./ssl/privkey.pem \
    -out    ./ssl/fullchain.pem \
    -subj   "/CN=minecraft.local"
```

浏览器会对自签名证书发出警告。添加一个永久例外，或把证书安装进操作系统的信任库。

### 方式 C —— 带密码保护的私钥

如果你的私钥被加密，把口令写进 `onlinechat-server.toml`（1.20.1 中位于
`world/serverconfig/` 或 `saves/<世界名>/serverconfig/`）：

```toml
[tls]
    privateKeyPassword = "your-passphrase"
```

> ⚠️ **切勿把 `./ssl` 提交到版本控制。** 模板已随附一份排除该文件夹的 `.gitignore`。

---

## 4. 在专用服务器上安装

1. 把 `onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar` 放入服务器的 `mods/` 文件夹
   （**`-all.jar`**，见第 2 节 —— 普通 jar 没有内置依赖）。
2. 确保相对于服务器工作目录存在 TLS 材料 —— 默认是 `./ssl/fullchain.pem` 与
   `./ssl/privkey.pem`（或设置 `tls.certDir` 指向它们所在的目录）。
3. 照常启动服务器（`java -jar ...` 或你的启动脚本）。
4. 留意日志中的：
   ```
   [OnlineChat] TLS material loaded: cert=..., key=...
   [OnlineChat] HTTPS/WebSocket server listening on https://0.0.0.0:8443/
   ```
5. 在防火墙中放行端口：
   * **Windows**：`New-NetFirewallRule -DisplayName "OnlineChat" -Direction Inbound -Protocol TCP -LocalPort 8443 -Action Allow`
   * **Linux (ufw)**：`sudo ufw allow 8443/tcp`

玩家客户端 **无需** 安装本模组。游戏内聊天与 `[是]` / `[否]` 确认按钮在原版客户端上即可工作。

---

## 5. 单人 / 局域网安装

同一个 jar 在单人模式下也能工作。打开世界时 Web 服务器启动，离开世界时停止。注意，
1.20.1 的 **服务端配置永远是每世界一份**：

* 世界的 `onlinechat-server.toml` 会创建在 `saves/<world>/serverconfig/` 内；
  而在 **专用** 服务器上它位于 `world/serverconfig/onlinechat-server.toml`
  （跟随世界文件夹，而不是 `config/`）。
* `onlinechat-common.toml` 是全局的（位于 `config/`）。
* `./ssl` 仍以 Minecraft 运行目录为基准解析（开发工作区中是 `run/`，生产环境中是启动器的
  实例文件夹）。

开发时你可以把 `tls.certDir` 设为 `../ssl`（或一个绝对路径）使其指向项目根目录，
或直接覆盖 `tls.certChainPath` / `tls.privateKeyPath`。

---

## 6. 开发工作区

本分支使用 **NeoGradle 6**（不是 ModDevGradle）。标准运行配置仍然存在：

```powershell
.\gradlew.bat runServer    # 专用服务器，便于测试网页 UI
.\gradlew.bat runClient    # 客户端 + 内置服务器
.\gradlew.bat runData      # 数据生成（本模组未使用）
```

* **必须使用 JDK 17 守护进程**（见第 1 节）—— NeoGradle 6 无法在 JDK 20+ 上运行。
* wrapper 把 Gradle 固定在 **8.1.1**（NeoGradle 6 不支持 Gradle 9）。
* 映射使用 **parchment** `2023.09.03-1.20.1`（NeoGradle 6 中 plain `official` 不可用）。
* 开发服务器通过 `build.gradle` 中的变通方案加载 `netty-codec-http`（`afterEvaluate`
  代码块把它追加到运行任务的 minecraft artifacts），因为 FML 1.20.1 只索引 legacy
  classpath 文件，不索引启动器 `-cp`。
* 源码里的 mods.toml 位于 `src/main/resources/META-INF/mods.toml`
  （NeoGradle 约定，值为字面量 —— 没有 `generateModMetadata` 模板处理）。

开发工作目录是 `run/`，因此把 `ssl/` 文件夹复制或软链接到那里：

```powershell
New-Item -ItemType Junction -Path .\run\ssl -Target ..\ssl
```

或编辑 `run/world/serverconfig/onlinechat-server.toml`（1.20.1 为每世界一份）并设置
绝对路径，例如 `tls.certDir = "../ssl"` 指向项目根目录。

---

## 7. 验证部署

* **健康检查**：
  ```
  curl -k https://localhost:8443/api/status
  ```
  预期响应：
  ```json
  {"ok":true,"service":"onlinechat","registration":true,"onlineWeb":0,"onlinePlayers":0,"maxPlayers":20,"motd":"...","style":{...}}
  ```
* **网页 UI**：在浏览器中打开 `https://<host>:8443/`，注册一个账号并登录。
* **绑定**：当你在游戏内在线时，在 *绑定 Minecraft 玩家* 面板中输入你的 Minecraft 用户名，
  然后在聊天提示上点击 **[是]**。
* **桥接**：在网页上输入一条消息，它应出现在游戏内并带有橙色的 `[Web Chat]` 标签。
  在游戏内输入，它会带着绿色的 `[In Game]` 标签出现在网页上。

如有任何失败，检查服务器日志中的 `[OnlineChat]` 条目，并参阅
[docs/zh/SECURITY.md](SECURITY.md) 与 [docs/zh/CONFIGURATION.md](CONFIGURATION.md)。

---

## 8. 从旧版本升级

用新 jar 覆盖旧 jar 并重启即可 —— 无需人工迁移。你现有数据的变化如下：

* **配置（`onlinechat-common.toml` / `onlinechat-server.toml`）** —— NeoForge 会以默认值补上新版本
  引入的每一个键，并保留你已有的值。现在越界的值会被纠正：旧的 `chatHistorySize = 0`
  （之前允许，现在最小值为 `1`）会变为新默认值 `300`。`[twoFactor]`、`[limits]` 等新节以及
  `language`、`certDir`、`webDir` 等键会自动出现。1.20.1 的服务端配置位于
  `world/serverconfig/onlinechat-server.toml`（专用服务器）或 `saves/<世界名>/serverconfig/`（单人）。
* **TLS 路径** —— 若你的配置仍带有旧默认值 `certChainPath = "./ssl/fullchain.pem"` /
  `privateKeyPath = "./ssl/privkey.pem"`（来自 `certDir` 出现之前），它们会被视为未设置，从而
  由 `certDir + certFileName/keyFileName` 接管；服务器会输出一条 INFO 提示你清空它们。
  其它 *自定义* 的显式路径仍优先，因此已有部署不受影响。
* **账号（`accounts.json`）** —— 原样读取；缺少较新 `twoFactorEnabled` 字段的记录默认为关闭 2FA。
  绑定、密码与盐值均保留。
* **网页会话** —— 旧的三段式登录令牌（`username.expiry.signature`）在过期前仍然有效，
  因此升级 **不会** 把所有人踢下线。新登录会获得当前的四段式令牌。
* **网页前端（`config/onlinechat/web`）** —— 利用隐藏的 `.exist` 清单就地升级：你从未编辑过的文件
  会刷新，新版本新增的文件会被复制进来，不再提供的文件会被删除（仅限未改动过的），
  而 **你编辑过的文件会保留**。`locales/*.json` 会做键级合并，自定义文案得以保留，同时新键仍会出现。
  若你改过的文件在上游也有变化，日志会以 WARN 列出，便于你手动合并。
* **强制干净重置** —— 删除 `config/onlinechat/web/.exist`（或整个 `web` 目录）并重启，即可重新
  释放原始默认文件，舍弃前端改动。配置、账号与聊天记录不会因此受影响。

升级前请务必备份 `config/onlinechat*` 以及你的 `accounts.json` / 聊天记录。
