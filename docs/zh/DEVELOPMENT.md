# 开发指南

> 语言：[English](../DEVELOPMENT.md) | **简体中文**

一次对源码树、运行时接线，以及你最可能改动的扩展点的巡览。

---

## 仓库结构（分支）

一套代码，**每个 Minecraft 世代一个分支** —— 三个 NeoForge 世代差异太大，无法用单个 jar 覆盖
（1.20.1 仍使用 `net.minecraftforge` 命名空间；事件、配置与组件 API 在 21.1 与 26.1 之间又发生了迁移）：

| 分支 | Minecraft | NeoForge | 加载器依赖 | 构建 JDK | 工具链 |
|------|-----------|----------|-----------|---------|--------|
| `master` | 1.21.1 | 21.1.250+ | `neoforge` | 21 | ModDevGradle 2.0.147 · Gradle 9.2.1 · Mojang 映射 |
| `mc/1.21.8` | 1.21.8 | 26.1.2.109+ | `neoforge` | 25 | ModDevGradle 2.0.147 · Gradle 9.2.1 · Mojang 映射 |
| **`mc/1.20.1`** *（本分支）* | 1.20.1 | 47.1.106+ | `forge` | 17 | NeoGradle 6.0.21 · Gradle 8.1.1 · parchment 2023.09.03 |

协作规则：

* **切勿跨分支合并构建脚本。** `build.gradle`、`gradle.properties`、`gradle/wrapper/*` 与
  `settings.gradle` 各自绑定一套工具链（ModDevGradle vs NeoGradle 6）；只 cherry-pick Java
  与网页资产的改动。
* **网页前端与大部分 Java 代码全分支共享** —— 例如 SharedWorker 套接字补丁会原样
  cherry-pick 到每个分支。
* 版本相关的 Java 差异很小且彼此隔离（配置 spec 类型、事件名、属性名、mods.toml 位置）。
* 本地发布 jar 放在被 git 忽略的 `release/` 目录：
  `git checkout <分支>` → `.\gradlew.bat build` → 把 jar 复制到 `release/`。
* CI（`.github/workflows/build.yml`）按分支选择 JDK：21（`master`）、21 + 工具链 25（`mc/1.21.8`）、
  17（`mc/1.20.1`）。

本分支值得注意的差异：

* **NeoGradle 6** 要求 **JDK 17 的 Gradle 守护进程**（不能是 JDK 20+），并且 wrapper 固定在
  **Gradle 8.1.1**（不支持 Gradle 9）。
* 映射使用 **parchment**（`2023.09.03-1.20.1`）—— NG6 下 1.20.1 的 plain `official` 不可用。
* mods.toml 位于 `src/main/resources/META-INF/mods.toml`，值为字面量（没有 `generateModMetadata`
  模板处理），`pack.mcmeta` 使用 `pack_format 15`。
* 服务端配置是 **每世界一份**（专用服务器位于 `world/serverconfig/onlinechat-server.toml`）
  —— 见 `ServerConfig` 的 javadoc。

---

## 项目结构

```
src/main/java/net/mcless/dev/onlinechat/
├── OnlineChat.java               # @Mod 入口 —— 持有运行时单例
├── OnlineChatClient.java         # @Mod(client) —— 注册配置界面
├── account/
│   ├── Account.java              # 存储在 accounts.json 中的 POJO
│   ├── AccountManager.java       # 加载/保存/注册/绑定，按名字与 UUID 建索引
│   ├── PasswordHasher.java       # PBKDF2-HMAC-SHA256，每账号独立盐
│   └── TokenService.java         # 无状态的 HMAC 签名令牌
├── auth/
│   └── TwoFactorGuard.java       # 2FA 进服冻结：属性/事件锁定、一次性 token、超时踢出
├── bridge/
│   ├── BindingManager.java       # 待处理绑定验证码、TTL、确认/拒绝
│   ├── ChatBridge.java           # 事件监听器 + 广播辅助 + 历史
│   ├── MessageStore.java         # 内存环 + JSONL 归档，分页历史
│   └── WebSessionManager.java    # 按通道与用户名索引的在线 WebSocket 会话
├── command/
│   └── OnlineChatCommand.java    # /onlinechat …… 命令树 + 可点击的 [是]/[否]
├── config/
│   ├── CommonConfig.java         # onlinechat-common.toml
│   └── ServerConfig.java         # onlinechat-server.toml
├── i18n/
│   └── Lang.java                 # 所有游戏内文本的服务端翻译
└── web/
    ├── HttpApiHandler.java       # REST + 静态资源 + WebSocket 握手
    ├── RateLimiter.java          # 按键滑动窗口限流
    ├── SslContexts.java          # PEM → Netty SslContext
    ├── WebAssets.java            # 将 web/ 释放到 storage.webDir，静态资源磁盘优先
    ├── WebServer.java            # Netty 引导
    └── WebSocketFrameHandler.java# JSON 帧协议

src/main/resources/
├── assets/onlinechat/lang/
│   ├── en_us.json                # 服务端翻译（聊天提示、命令反馈、配置）
│   └── zh_cn.json
└── web/                          # 由内置 HTTPS 服务器提供的多页面 UI
    ├── index.html   index.js     # 落地页 —— 重定向到登录或聊天
    ├── login.html   login.js     # 标签页式登录 / 注册
    ├── account.html account.js   # 账号页：MC 绑定（WebSocket 驱动）、2FA 开关、改密、注销
    ├── 2fa.html     2fa.js       # 2FA 确认页，路径为 /2fa/auth/<token>
    ├── chat.html    chat.js      # 实时聊天桥接
    ├── common.js                 # 共享的 API / i18n / 认证 / toast 辅助
    ├── style.css                 # 深色玻璃拟态主题
    └── locales/en.json, zh-CN.json   # 前端 UI 词典

src/main/resources/META-INF/mods.toml   # NeoGradle 6 约定：字面量值，无模板处理
src/main/resources/pack.mcmeta          # pack_format 15
```

---

## 运行时接线

```
ServerStartingEvent
      │
      ▼
OnlineChat.onServerStarting
      │  解析运行目录（工作目录）
      │  Lang.load(ServerConfig.language)  ← 服务端 i18n 词典
      │  new AccountManager(...).load()
      │  new TokenService(...).init()      ← 首次运行时生成 token.secret
      │  new MessageStore(...).load()      ← 聊天环 + JSONL 归档
      │  new WebSessionManager()
      │  new ChatBridge(sessions, accounts, messages).setServer(mc)
      │  new BindingManager(accounts, bridge)
      │  new TwoFactorGuard(accounts).setServer(mc)
      │  NeoForge.EVENT_BUS.register(bridge); register(twoFactor)
      │  new WebAssets(webDir).extractIfNeeded()  ← <webDir>/.exist 不存在则释放 web/，存在则按清单升级
      │  new WebServer(...)                ← 已构造，尚未启动
      ▼
ServerStartedEvent
      │
      ▼
WebServer.start()
      │  SslContexts.buildServerContext(runDir)   ← 读取 <certDir>/*.pem（默认 ./ssl）
      │  Netty 管线：
      │      SslHandler → IdleStateHandler → HttpServerCodec →
      │      HttpObjectAggregator → ChunkedWriteHandler →
      │      HttpApiHandler → WebSocketFrameHandler
      ▼
就绪。客户端连接到 https://host:port/
```

在 `ServerStoppingEvent` 时管线反向执行：浏览器收到 `server_shutdown` 帧，Netty 通道被关闭，
事件循环组优雅关停，`ChatBridge` 与 `TwoFactorGuard` 从事件总线注销，`AccountManager.save()`
刷入所有待处理的变更。

---

## 关键设计决策

### 为什么用 Netty（而不是 `com.sun.net.httpserver` 或第三方库）？

Minecraft 1.20.1 捆绑了 Netty 4.1.82.Final 的大部分模块（`netty-handler`、`netty-transport`、
`netty-codec`……），唯独缺少包含本模组所需 HTTP/WebSocket 编解码器的 `netty-codec-http`。
使用 Netty 意味着：

* **无需额外安装任何东西** —— `netty-codec-http` 是唯一随 jar 分发的构件，通过 **JarInJar** 打进
  发布的 `-all.jar`（`build.gradle` 中 `jarJar('io.netty:netty-codec-http:[4.1.82.Final,4.1.83)')`
  外加 `jarJar.enable()` —— NeoGradle 6 默认禁用 jarJar 任务）；其余全部来自 Minecraft 自身，
  运行时类路径上不会有重复的 Netty 类。
* 对 WebSocket 协议（`WebSocketServerHandshaker`、`TextWebSocketFrame`）与 TLS
  （`SslContextBuilder.forServer(File, File)`）的原生支持。
* 久经考验的事件循环模型，与 Minecraft 自身的网络层一致。

编译类路径需要对 Netty 核心构件显式声明 `compileOnly`（见 `build.gradle`），因为 NeoGradle
不会重新导出 Minecraft 的传递依赖。运行时 Netty 核心类由 Minecraft 自身提供，
`netty-codec-http` 则通过 JarInJar 打进 `-all.jar`。

**本分支专属的开发运行时怪癖：** NeoGradle 6 会把 jarJar/项目依赖放进启动器 `-cp`，
但 FML 1.20.1 的类加载层是从运行的 *legacy classpath 文件*
（`build/classpath/runServer_minecraftClasspath.txt`，完全由 `minecraft` 配置喂给）构建的，
从不索引普通 `-cp` —— 因此除非把该 jar 追加到运行任务的 *minecraft artifacts*，
`runServer` 中每个请求都会因 `NoClassDefFoundError: HttpServerCodec` 挂掉。
`build.gradle` 底部的 `afterEvaluate` 代码块正是为 `runServer`/`runClient`/`runData`/
`runGameTestServer` 做了这件事。`minecraft` 配置本身必须保持单依赖
（"must contain exactly one dependency"），所以变通方案走的是任务的 `minecraftArtifacts` 集合。

### 为什么用无状态 HMAC 令牌而不是会话？

* 没有会在重启时丢失的服务端状态。
* 易于横向扩展（尽管本模组本质上是单服务器的）。
* 轮换密钥可一步让所有令牌失效。

对吊销的唯一让步：`issuedAt` 早于账号 `lastLoginAt` 的令牌会被拒绝。修改密码（或注销账号）
会更新该时间戳，因此无需服务端会话表即可让其它所有设备下线。

### 为什么 web → game 用 `broadcastSystemMessage`？

`ServerChatEvent` 只对源自 `ServerPlayer` 的消息触发。通过
`MinecraftServer.getPlayerList().broadcastSystemMessage(component, false)` 广播会完全跳过
该事件，这意味着：

* 不会有回声循环回到网页。
* 不会意外执行 `/command` 前缀（原版聊天管线的一个怪癖）。
* 对渲染出的 `Component`（包括带颜色的前缀）有完全的控制权。

### 为什么用 `[是]` 点击而不是在聊天中输入验证码？

在聊天中输入的验证码会泄漏进日志、对其他玩家可见，并要求用户切换焦点。
一个 `ClickEvent.Action.RUN_COMMAND` 链接会以一次性验证码运行一条隐藏命令 ——
别人既看不到也无法重放它，且由玩家自己的客户端确认这一意图。

### 为什么 2FA 用属性 + 事件冻结而不是 Mixin / 包过滤？

`TwoFactorGuard` 通过 `ADD_MULTIPLIED_TOTAL -1` 修饰符把移动速度、飞行速度与跳跃强度归零
（1.20.1 没有重力 / 方块交互距离 / 实体交互距离属性，因此冻结依赖速度归零加上被取消的
交互 / 攻击 / 使用物品 / 丢弃 / 命令事件），并每 tick 把玩家拉回进服位置。这全部是
NeoForge 公开 API：不 Mixin 网络层，因而不会与替换 tick 或区块管线的
模组（Create、Sable……）冲突。代价是冻结期间客户端仍会收到世界数据包 —— 只是玩家无法
对其做任何操作。

Token 为 32 字节随机数（base64url）、一次性、与进服玩家的 UUID 绑定，并随
`twoFactor.timeoutSeconds` 过期。网页端用 `oc_token` cookie 确认：浏览器中的账号必须正是
绑定到该玩家的账号，否则玩家被踢出。

### 为什么在服务端翻译而不是分发客户端语言文件？

服务端模组不能假设每个客户端都装了本模组（或资源包）。`Lang` 从 jar 中加载
`assets/onlinechat/lang/<language>.json` 并生成字面量 `Component`，因此原版客户端也能看到
翻译后的文本。缺失的键回退到 `en_us`。

---

## 扩展模组

### 新增一个被桥接的系统事件

1. 在 `CommonConfig.SYSTEM_MESSAGE_KINDS` 的白名单中加入一个 kind 字符串。
2. 在 `ChatBridge` 中添加一个 `@SubscribeEvent` 处理器，以 `shouldBridge("<yourKind>")` 为门控。
3. 用 `rememberAndBroadcast(new ChatMessage(..., Kind.SYSTEM, ..., "<yourKind>"))` 发出。
4. 可选地教网页 UI 特别渲染它（见 `web/chat.js` 中的 `appendMessage`）。

### 新增一个 REST 端点

1. 在 `HttpApiHandler.handleApi` 的 switch 中加一个 case。
2. 实现一个 `handleXxx(ChannelHandlerContext, FullHttpRequest)` 方法。
3. 用 `auth(req)` 要求令牌，或对公开端点跳过它。
4. 用 `sendJson(ctx, req, HttpResponseStatus.OK, jsonObject)` 回复。

### 新增一种 WebSocket 消息类型

1. 扩展 `WebSocketFrameHandler.channelRead0` 中的 switch。
2. 用新的帧结构更新 `docs/WEB_API.md`。
3. 在相关页面脚本（例如 `web/chat.js`）中处理这个新类型。

### 替换存储后端

`AccountManager` 是唯一接触磁盘的地方。把它的 `load()`/`save()` 换成 SQLite、MariaDB
或一次 HTTP 调用即可 —— 代码库的其余部分使用内存中的 `Account` POJO，从不接触存储层。

### 自定义网页 UI

无需重新构建 jar：首次启动时，内置的 `web/` 目录会连同一个隐藏的 `.exist` 标记一起释放到
`storage.webDir`（默认 `config/onlinechat/web`），静态文件 **磁盘优先**、jar 兜底（`WebAssets`）。
直接编辑那里的文件；`.exist` 标记是一份 SHA-256 **清单**，因此后续升级时模组只会刷新你从未改动
的文件、保留你的编辑，并对 `locales/*.json` 做键级合并。删除该标记即可重新释放原始默认文件。

若要整体替换，用你自己的构建替换 `src/main/resources/web/*`。API 契约记录在
[WEB_API.md](WEB_API.md) 中，并在补丁版本之间保持稳定。把 `index.html` 保留在
`/web/index.html` 以便回退路由可用；若保留 2FA，需为 `/2fa/auth/<token>` 提供 `2fa.html`。

### 从旧版本无缝升级

升级被设计为无需人工迁移：

* **配置 TOML** —— NeoForge 会用新默认值自动补齐旧 `onlinechat-*.toml` 中缺失的键，并把越界值
  纠正到范围内（例如旧的 `chatHistorySize = 0` 会被纠正为新默认值）。新增的节（`[twoFactor]`、
  `[limits]`、`language`、`certDir`、`webDir` 等）会自动出现。
* **TLS 路径** —— 旧配置可能仍带有 `certDir` 出现之前的默认值
  `certChainPath = "./ssl/fullchain.pem"` / `privateKeyPath = "./ssl/privkey.pem"`。
  `SslContexts` 会把这两个字面量视为 *未设置*（并 INFO 提示一次），使新的
  `certDir + fileName` 键生效；其它显式路径仍优先。
* **账号** —— `accounts.json` 用 Gson 读取；缺少较新的 `twoFactorEnabled` 字段的记录默认为
  `false`，无需重写。
* **令牌** —— `TokenService` 仍接受旧的三段式 `username.expiry.signature` 直到其过期，
  并据过期时间与配置的 TTL 推算 `issuedAt`，因此升级不会强制所有网页用户重新登录。
* **网页前端** —— 见上文；清单驱动逐文件合并，取代旧版「有 `.exist` 就完全不动」的策略。

### 新增或修改游戏内文本

1. 把键 **同时** 加入 `assets/onlinechat/lang/en_us.json` 与 `zh_cn.json`
   （缺失键会回退到 `en_us`，但请保持同步）。
2. 需要 `MutableComponent` 时用 `Lang.text("onlinechat.your.key", args...)`，需要纯 `String`
   时用 `Lang.tr`，参数本身是带样式组件时用 `Lang.component(key, Component...)`。占位符遵循
   `String.format`（`%s`）。

---

## 调试技巧

* 在 `onlinechat-server.toml` 中打开 `verboseLogging = true`，并用 `/onlinechat reload`
  重启。每个 HTTP 请求与 WebSocket 认证都会连同远程地址一起记录。
* Netty 的 `IdleStateHandler` 设为 120 秒。如果你的客户端位于空闲超时更短的代理之后，
  每 ~60 秒从 JS 发送一个 `ping` 帧。
* 聊天历史环受 `storage.chatHistorySize` 约束（最小值 `1`）。把它调小可几乎不在内存中保留
  历史，这在调试内存压力时很有用；更早的消息仍会从 `chatLogFile` 分页读取。
* `curl -vk https://localhost:8443/api/status` 会显示完整的 TLS 握手；当 Netty 拒绝某种
  证书格式时很有用。
* 如果模组以 `TLS private key not found` 启动失败，检查 `tls.certDir` 与 `tls.keyFileName`
  （或 `tls.privateKeyPath` 覆盖项），并记住相对路径以 JVM 的 **工作目录** 为基准解析，
  而非模组 jar 的位置。

---

## 构建与发布

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'   # NeoGradle 6 需要 JDK ≤ 20 的守护进程
.\gradlew.bat build                    # 产出 onlinechat-1.20.1-neoforge-0.0.3-alpha.jar 与 -all.jar
.\gradlew.bat publish                  # 发布到本地 ./repo maven（见 build.gradle）
```

在切分发布版本前，先提升 `gradle.properties` 中的 `mod_version`。本分支的版本号以字面量
形式写在 `src/main/resources/META-INF/mods.toml` 里 —— 请保持同步（NeoGradle 6 没有
`generateModMetadata` 模板处理）。

每个版本的发布流程：

1. `git checkout <分支>`（本分支构建 1.20.1）。
2. `.\gradlew.bat build`，并运行 E2E 套件（本地 `%TEMP%\oc-e2e\server-driver.ps1`
   —— HTTP/HTTPS、WebSocket、REST 与 RCON 检查）。
3. 把 **`build/libs/onlinechat-1.20.1-neoforge-0.0.3-alpha-all.jar`**（JarInJar 产物 ——
   普通 jar 不可运行）复制进被 git 忽略的 `release/` 目录。
4. 对 `master`（1.21.1）与 `mc/1.21.8` 重复以上步骤。

---

## 测试检查清单（手动）

目前还没有自动化测试 —— 接入面足够小，手动过一遍即可覆盖全部。在交付一个改动前，逐项走查：

1. 全新安装：注册 → 登录 → 看到空历史。
2. 绑定到一个离线玩家 → 清晰的错误，不发送 `[是]`。
3. 绑定到一个在线玩家 → 提示出现，点击 **[是]**，网页 UI 更新。
4. 在 `allowRebind = false` 时再次绑定 → 报错。
5. 从网页发送一条聊天 → 出现在游戏内并带橙色前缀。
6. 从游戏发送一条聊天 → 出现在网页上并带绿色前缀。
7. 在游戏内死亡 → 网页上出现系统消息（如果 `death` 在 `systemMessageKinds` 中）。
8. 断开 WebSocket（关闭标签页）→ 游戏内出现 `... disconnected from the web chat`
   （如果 `bridgeWebPresence = true`）。
9. 重启服务器 → 下次连接时历史重放，令牌仍然有效。
10. 轮换 `token.secret` → 旧令牌被拒绝，用户必须重新登录。
11. 首次启动 → `config/onlinechat/web/` 被释放且含隐藏 `.exist`（一份 SHA-256 清单）；改动 `style.css` 后重启仍保留；
    删除 `.exist` 重启 → 文件被覆写。
11b. 就地升级 → 改动 `style.css` 后，用更新版本的 jar 替换并重启：未改动文件刷新、改动过的 `style.css` 保留
    （若上游也改了则 WARN）、新文件出现、已删除且未改动的文件被清理、`locales/*.json` 补齐新键。
12. `language = "zh_cn"` → 绑定提示、`/onlinechat` 反馈均为中文；未知语言码回退到英文。
13. `twoFactor.enabled = true`，账号页开启 2FA → 进服后无法移动/交互/发命令，聊天栏出现链接；
    浏览器已登录且为绑定账号 → 点「是我」放行；其它账号 → 踢出；超时 → 踢出。
14. 账号页改密 → 本页保持登录，其它设备跳转登录页并提示；注销账号 → 玩家自动解绑、所有会话下线。
