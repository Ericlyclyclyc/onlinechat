# 配置

> 语言：[English](../CONFIGURATION.md) | **简体中文**

Online Chat 使用 **两个** 配置文件，二者都会在首次启动时以合理的默认值自动创建。

| 文件 | 作用域 | 内容 |
|------|--------|------|
| `config/onlinechat-common.toml`  | 全局（两端都会加载） | 聊天桥接行为、前缀、格式化 |
| `config/onlinechat-server.toml`  | 仅服务端 | HTTPS 监听器、TLS 材料、认证、存储、CORS |

> 在内置服务器（单人）上，`onlinechat-server.toml` 会写入 `saves/<world>/serverconfig/`
> 而不是 `config/`。

在客户端上，两个文件都可以通过游戏内的 **Mods → Online Chat → Config** 界面热编辑。
在专用服务器上，手动编辑它们并运行 `/onlinechat reload`（仅限 OP）即可在不完整重启的情况下
重启 Web 监听器。

---

## `onlinechat-common.toml`

```toml
# 总开关：把游戏内聊天桥接到网页平台。
bridgeEnabled = true
# 把非玩家聊天消息（加入、退出、死亡、进度）桥接到网页平台。
bridgeSystemMessages = true
# 应桥接哪些非玩家消息。有效值：join、quit、death、advancement。
systemMessageKinds = ["join", "quit", "death", "advancement"]
# 当网页用户连接或断开 WebSocket 时，在游戏侧广播一行聊天。
bridgeWebPresence = true

[prefixes]
    # 当消息来自网页时，游戏内聊天中显示在发送者名字之前的文本。
    webPrefixText = "[Web Chat]"
    # [Web Chat] 前缀的颜色。任意 ChatColor 名称（GOLD、YELLOW、RED……）。偏橙的默认值：GOLD。
    webPrefixColor = "GOLD"
    # 当消息来自游戏时，网页上显示在发送者名字之前的文本。
    inGamePrefixText = "[In Game]"
    # 网页上应用于 [In Game] 前缀的 CSS 颜色。
    inGamePrefixColor = "#2ecc71"
    # 网页上应用于 [Web Chat] 前缀的 CSS 颜色。
    webPrefixColorCss = "#e67e22"

[format]
    # 网页用户的消息转发进游戏内聊天时使用的格式。
    # 占位符：{prefix} {name} {message}
    gameChatFormat = "{prefix} <{name}> {message}"
    # 在把游戏消息转发到网页之前，剥离 Minecraft 格式化代码（分节符序列）。
    stripFormatting = true
    # 网页用户可发送的聊天消息的最大长度。
    maxWebMessageLength = 500
```

### 前缀

| 键 | 含义 |
|----|------|
| `webPrefixText` | **游戏内** 显示在网页用户名字之前的橙色标签文本。 |
| `webPrefixColor` | 应用于该标签的 Minecraft `ChatFormatting` 名称。可为 `BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE` 之一。 |
| `inGamePrefixText` | **网页上** 显示在游戏玩家名字之前的绿色标签文本。 |
| `inGamePrefixColor` | 网页上应用的 CSS 颜色字符串（`#rrggbb`、`rgb()`、颜色名……）。 |
| `webPrefixColorCss` | 当把某网页用户的消息回放给其他网页用户时，网页上应用的 CSS 颜色字符串。 |

### 格式占位符

`gameChatFormat` 支持三个占位符：

| 占位符 | 替换为 |
|--------|--------|
| `{prefix}` | 带颜色的 `[Web Chat]` 标签 |
| `{name}` | 发送者的显示名（已绑定则为绑定的 MC 名字，否则为网页用户名） |
| `{message}` | 原始消息文本（广播前 `§` 字符会被转义为 `&`） |

示例：`"{prefix} {name} » {message}"` 会渲染为 **[Web Chat] Steve » hello**。

---

## `onlinechat-server.toml`

```toml
# 本模组在游戏内发送的所有文本（聊天提示、命令反馈）所使用的语言。
# 可用 assets/onlinechat/lang/ 下的任意语言文件（en_us、zh_cn）。缺失的键回退到 en_us。
language = "en_us"
# 当 Minecraft 服务器启动时，启动内置的 HTTPS/WebSocket 服务器。
enabled = true
# HTTPS 服务器的绑定地址。用 0.0.0.0 监听所有网卡。
host = "0.0.0.0"
# HTTPS 服务器（默认的加密监听器）的 TCP 端口。
port = 8443
# 在 HTTPS 之外，是否同时启动一个明文 HTTP（未加密）监听器。
httpEnabled = false
# 明文 HTTP 监听器的 TCP 端口。仅当 httpEnabled = true 时使用。
httpPort = 8080

[tls]
    certDir = "./ssl"
    certFileName = "fullchain.pem"
    keyFileName = "privkey.pem"
    certChainPath = ""
    privateKeyPath = ""
    privateKeyPassword = ""
    requireClientAuth = false

[auth]
    allowRegistration = true
    minPasswordLength = 8
    pbkdf2Iterations = 210000
    tokenTtlMinutes = 1440
    tokenSecret = ""
    maxLoginAttempts = 8
    loginCooldownSeconds = 300

[binding]
    bindCodeTtlSeconds = 120
    allowRebind = true

[storage]
    accountsFile = "onlinechat/accounts.json"
    chatHistorySize = 300
    chatPageSize = 30
    chatLogFile = "onlinechat/chat_history.jsonl"
    webDir = "config/onlinechat/web"

[twoFactor]
    enabled = false
    publicUrl = ""
    timeoutSeconds = 120

[cors]
    allowedOrigins = ["*"]

[limits]
    maxConnectionsPerIp = 8
    maxConnectionsTotal = 200
    maxChatMessagesPerMinute = 20
    registerAttemptsPerHour = 5
    bindRequestsPerMinute = 3

verboseLogging = false
```

### `[tls]`

TLS 材料通过两步定位。第一步，如果 `certChainPath` / `privateKeyPath` 被设为非空值，
则原样使用它们（绝对路径，或相对于运行目录）。否则，文件由 `certDir` + 对应的文件名
拼成（`<certDir>/<certFileName>` 与 `<certDir>/<keyFileName>`）。使用随附的默认值时，
这会解析为 `./ssl/fullchain.pem` 与 `./ssl/privkey.pem`。

| 键 | 默认值 | 说明 |
|----|--------|------|
| `certDir` | `./ssl` | 存放 PEM 文件的目录。相对路径以 Minecraft 运行目录为基准解析；绝对路径原样使用。这是最简单的旋钮 —— 例如把它指向你的 Let's Encrypt `live/<domain>/` 文件夹。 |
| `certFileName` | `fullchain.pem` | `certDir` 内的证书文件 —— 叶子证书 **及** 所有中间证书。Let's Encrypt 的 `fullchain.pem` 正是如此。 |
| `keyFileName` | `privkey.pem` | `certDir` 内的私钥文件。首选 PKCS#8（`BEGIN PRIVATE KEY`）；PKCS#1（`BEGIN RSA PRIVATE KEY`）在 Netty ≥ 4.1.71（随 MC 1.21.1 捆绑）下也可用。 |
| `certChainPath` | *（空）* | 证书的可选显式路径覆盖。非空时优先于 `certDir` + `certFileName`。 |
| `privateKeyPath` | *（空）* | 私钥的可选显式路径覆盖。非空时优先于 `certDir` + `keyFileName`。 |
| `privateKeyPassword` | *（空）* | 私钥被加密时的口令。未加密的私钥留空即可。 |
| `requireClientAuth` | `false` | 启用双向 TLS（mTLS）。几乎从不需要 —— 仅在你签发客户端证书时才开启。 |

所有相对路径（`certDir` 与显式覆盖路径皆是）以 Minecraft 进程的 **工作目录** 为基准解析。
绝对路径原样使用。

### 监听器：`host`、`port`、`httpEnabled`、`httpPort`

服务器总会尝试在 `host:port` 上启用 **HTTPS** 监听器（这是默认且推荐的接入面）。
与此独立，当 `httpEnabled = true` 时，它还会在 `host:httpPort` 上绑定一个 **明文 HTTP**
监听器，共享同一套 Netty 事件循环以及同一套 REST/WebSocket API。

| 键 | 默认值 | 说明 |
|----|--------|------|
| `host` | `0.0.0.0` | **两个** 监听器的绑定地址。 |
| `port` | `8443` | HTTPS 端口。需要有效的 TLS 材料（见 `[tls]`）。 |
| `httpEnabled` | `false` | 额外的未加密 HTTP 监听器的开关。 |
| `httpPort` | `8080` | HTTP 端口。除非 `httpEnabled = true`，否则被忽略。 |

行为说明：

* 两个监听器相互独立：如果 TLS 材料缺失或无效，HTTPS 监听器会失败并记录错误，但已启用的
  HTTP 监听器仍会启动（反之亦然）。只要 **至少一个** 监听器成功绑定，Web 服务器就报告为
  “运行中”。
* 在 HTTPS 监听器上，`oc_token` 认证 Cookie 会带上 `Secure` 标志；在明文 HTTP 监听器上，
  该标志会被 **省略**，以便浏览器能在 `http://` 下存储并发送它。WebSocket 握手同理：
  HTTPS 上通告 `wss://`，HTTP 上通告 `ws://`。
* **安全性：** 明文 HTTP 会以明文传输凭据和聊天内容。仅在 TLS 终止的反向代理之后，
  或隔离的局域网测试中启用它 —— 切勿直接暴露到公网。

### `language`

本模组产生的所有游戏内文本（绑定提示、`/onlinechat` 反馈、2FA 通知、桥接到网页的加入/退出行）
均在 **服务端** 按 `assets/onlinechat/lang/<language>.json` 翻译，玩家无需安装客户端模组或资源包。
随附：`en_us`、`zh_cn`。缺失的键回退到 `en_us`。修改后执行 `/onlinechat reload` 生效。

### `[auth]`

| 键 | 说明 |
|----|------|
| `allowRegistration` | 设为 `false` 可关闭新注册（已有账号仍可正常使用）。 |
| `minPasswordLength` | 在 `/api/register` 上由服务端强制执行。 |
| `pbkdf2Iterations` | PBKDF2-HMAC-SHA256 迭代次数。若 CPU 预算允许，OWASP 2023 建议 SHA-256 用 ≥ 600 000；210 000 是合理默认值，在一般硬件上仍然敏捷。 |
| `tokenTtlMinutes` | 登录时签发的令牌的生命周期。 |
| `tokenSecret` | HMAC 密钥。留空则自动生成一个 48 字节随机密钥，并持久化到账号文件旁（`onlinechat/token.secret`）。仅当你需要令牌在移动账号文件后依然有效时，才设置固定值。 |
| `maxLoginAttempts` | 冷却窗口内每个 IP 的失败登录次数。`0` 禁用该限制器。 |
| `loginCooldownSeconds` | 冷却窗口的时长。 |

### `[binding]`

| 键 | 说明 |
|----|------|
| `bindCodeTtlSeconds` | 玩家在验证码过期前点击 **[是]** 的时间窗口。 |
| `allowRebind` | 已绑定的网页账号是否可以请求新的绑定（仍需在游戏内确认）。 |

### `[storage]`

| 键 | 说明 |
|----|------|
| `accountsFile` | 存储网页账号与绑定的 JSON 文件。相对路径以工作目录为基准解析。 |
| `chatHistorySize` | 保存在内存中的近期消息条数。更早的消息按需从 `chatLogFile` 分页读取。 |
| `chatPageSize` | 每页消息数：WebSocket 连接时的首批重放，以及每次“向上滚动加载更多”的请求。 |
| `chatLogFile` | 仅追加的 JSON Lines 聊天归档。 |
| `webDir` | 随附的网页前端 **释放并保持更新** 的目录（见下文）。 |

#### 自定义网页前端（`webDir`）

服务器首次启动时，模组会把内置 `web/` 目录下的全部文件（HTML、JS、CSS、`locales/*.json`）复制到
`webDir`（默认 `config/onlinechat/web`），并在其中放置一个隐藏标记文件 `.exist`。该文件同时充当
**清单**：记录了写入时 jar 内每个文件的 SHA-256，模组升级时据此区分你的改动与原始文件：

* 请求 **优先从 `webDir` 提供**；仅当磁盘上缺少某文件时才回退到 jar 内的副本。你可以修改
  `style.css`、调整 `locales/zh-CN.json` 中的文案、添加 logo 等。
* 升级时（`.exist` 存在）：你从未改动的文件会刷新为新版本内置副本，新版本新增的文件会被复制进来，
  新版本已不再提供的文件会被删除（仅限未改动过的），而 **你编辑过的文件原样保留**。若你改过的
  文件在新版本中也有变化，日志会以 WARN 列出，便于你手动合并。
* 从磁盘读取的 `locales/*.json` 还会与 jar 内副本做 **键级合并**，自定义翻译不会缺少新版本
  新增的键。
* 删除 `.exist`（或整个目录）后重启，即可重新释放原始默认文件并整体覆写。

### `[twoFactor]`

用于 **进入 Minecraft 服务器** 的可选第二因素。默认关闭；管理员开启后，由每位玩家在网页
**账号** 页面自行决定是否启用（需先绑定 Minecraft 玩家）。

| 键 | 默认值 | 说明 |
|----|--------|------|
| `enabled` | `false` | 总开关。为 `false` 时不会冻结任何人，网页上的开关呈灰色。 |
| `publicUrl` | *（空）* | 玩家浏览器可访问的基础 URL，例如 `https://play.example.com:8443`（末尾不带斜杠）。会拼接到聊天链接中的 `/2fa/auth/<token>` 前面。留空则回退为 `https://<host 或本机 IP>:<port>`，公网服务器上通常不是你想要的。 |
| `timeoutSeconds` | `120` | 被冻结的玩家在被踢出前，完成浏览器确认的时限（15–600）。 |

绑定的网页账号已开启 2FA 的玩家，其流程为：

1. 进服后玩家被 **冻结**：移动/跳跃/飞行/重力/交互距离等属性归零；聊天、命令、方块/实体交互、
   攻击、物品使用/丢弃/拾取以及受到的伤害均被取消，并关闭已打开的界面。不涉及 Mixin，
   其他模组自身逻辑不受影响。
2. 聊天栏显示可点击的一次性链接 `publicUrl + /2fa/auth/<32 字节随机 token>`，并在剩余 60/30/10 秒时提醒。
3. 在持有 **匹配** 网页账号 `oc_token` Cookie 的浏览器中打开链接，会显示等待中的玩家以及两个按钮：
   *是我本人*（放行）与 *不是我*（踢出）。没有 Cookie 的浏览器会先经过登录页再返回。
   若浏览器登录的是 **其他** 账号则无法放行；强行尝试会导致玩家被踢出。
4. 超时 → 踢出。Web 服务器未运行 → 跳过 2FA 并在日志中输出 WARN，避免任何人被永久锁在门外。

### `[cors]`

| 键 | 说明 |
|----|------|
| `allowedOrigins` | 允许调用 REST/WebSocket API 的来源列表。`["*"]` 允许任意来源 —— 当网页 UI 由同一台服务器提供时没问题，但如果你把 API 暴露给独立的前端则很危险。 |

### `[limits]`

滥用 / 资源耗尽限制。每个值都接受 `0` 以禁用该项特定限制。

| 键 | 默认值 | 说明 |
|----|--------|------|
| `maxConnectionsPerIp` | `8` | 单个 IP 允许的并发 WebSocket 连接数。超出的握手以 HTTP 429 拒绝。 |
| `maxConnectionsTotal` | `200` | 全局并发 WebSocket 连接上限。超出后握手以 HTTP 503 拒绝。 |
| `maxChatMessagesPerMinute` | `20` | 每会话聊天节流。首条超额消息返回错误；窗口重置前，后续刷屏被静默丢弃。 |
| `registerAttemptsPerHour` | `5` | 每 IP 每小时的 `/api/register` 调用次数（防批量注册）。超额尝试返回 HTTP 429。 |
| `bindRequestsPerMinute` | `3` | 每个网页账号每分钟的绑定请求数，在 `BindingManager` 中强制执行，因此同时覆盖 REST 与 WebSocket 两条路径（防止对在线玩家进行弹窗骚扰）。 |

> 登录限制器（`[auth]` 中的 `maxLoginAttempts` / `loginCooldownSeconds`）与上述限制
> **在所有连接之间共享**。它们按 IP（绑定则按账号）为键，而非按 TCP 连接，
> 因此重新连接不会重置它们。

### `verboseLogging`

为 `true` 时，每个 HTTP 请求与 WebSocket 认证事件都会以 INFO 级别记录。
仅供调试时开启 —— 输出非常嘈杂。

---

## 重新加载

* 编辑 `onlinechat-common.toml` **立即** 生效（值按事件逐个读取）。
* 编辑 `onlinechat-server.toml` 需在 `/onlinechat reload`（仅限 OP）或完整重启服务器后生效。
  reload 会重新读取 TLS 材料，因此它也能读取续期后的 Let's Encrypt 证书。

---

## 游戏内命令

| 命令 | 权限 | 描述 |
|------|------|------|
| `/onlinechat bind confirm <code>` | 任意玩家 | 玩家在绑定提示中点击 **[是]** 时自动运行。 |
| `/onlinechat bind deny <code>`    | 任意玩家 | 玩家点击 **[否]** 时自动运行。 |
| `/onlinechat status`              | 任意玩家 | 显示该玩家是否已绑定、绑定到哪个网页账号，以及是否开启了 2FA。 |
| `/onlinechat unbind`              | 任意玩家 | 移除你自己的绑定（同时关闭该账号的 2FA）。 |
| `/onlinechat reload`              | OP（等级 2） | 重启 Web 监听器（HTTPS，以及若启用的 HTTP）并重新加载配置、语言文件 + TLS 材料。 |
| `/onlinechat account setpassword <username> <password>` | OP（等级 2） | 重置某网页账号的密码，并使其在所有设备上退出登录。 |
| `/onlinechat account delete <username>` | OP（等级 2） | 注销某网页账号：解绑其玩家、关闭 2FA、关闭其会话。 |

玩家修改自己的密码 / 注销自己的账号请在网页 **账号** 页面操作；这两个 `account` 子命令用于
管理员协助被锁在门外的用户。
