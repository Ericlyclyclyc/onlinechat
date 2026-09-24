# Web API 参考

> 语言：[English](../WEB_API.md) | **简体中文**

模组在同一个 HTTPS 监听器上暴露两个接入面：

* `/api/*` 下一个小型的 **REST** API，用于认证、绑定与状态查询。
* `/ws` 处的一个 **WebSocket** 端点，用于实时聊天与实时绑定更新。

自带的网页 UI（`/`）恰好使用这些 API，因此本文档记录的任何内容都可以安全地从你自己的
前端调用。

所有请求体均为 JSON，所有响应都会根据 `onlinechat-server.toml` 中的 `cors.allowedOrigins`
包含 `Access-Control-Allow-Origin`。

---

## 认证模型

* 注册或登录 → 获得一个 **令牌**（HMAC-SHA256 签名，形如 `username.issuedAt.expiry.signature`）。
  旧版本签发的三段式令牌（`username.expiry.signature`）在过期前仍然有效。
* 服务器在 `/api/login` 与 `/api/register` 上把令牌设为一个 **`oc_token` Cookie**
  （`HttpOnly; Secure; SameSite=Lax`），因此自带 UI 从不在 JS 中接触令牌。
* 在随后的每个请求中，用以下任意一种方式携带令牌（按此顺序检查）：
  * `Authorization: Bearer <token>` 请求头（REST）
  * `X-Auth-Token: <token>` 请求头（REST）
  * `oc_token` Cookie（REST **与** WebSocket）
  * `{ "type":"auth", "token":"<token>" }` 作为第一个 WebSocket 帧
* 对于 WebSocket，浏览器无法在升级请求上设置自定义请求头，因此服务器从 **握手的
  Cookie / Authorization 请求头** 中读取令牌并自动认证该通道 —— 使用 Cookie 时无需显式的
  `auth` 帧。
* `POST /api/logout` 会清除 Cookie（`Max-Age=0`），并断开属于该令牌的所有在线 WebSocket 会话。
* 令牌在 `auth.tokenTtlMinutes`（默认 1440 = 24 小时）后过期；Cookie 的 `Max-Age` 与之匹配。
  当客户端收到 `401` 或 `auth_error` 时应重新登录。

令牌是无状态的 —— 没有服务端会话，因此扩展与重启的开销都很低。
轮换 `tokenSecret`（或删除 `onlinechat/token.secret`）会立即让所有未过期的令牌失效。

> **Cookie 标志说明：** `oc_token` Cookie 被标记为 `Secure`，因此它只会通过 HTTPS 发送。
> 请通过 TLS 监听器（默认）提供 UI —— 纯 HTTP 部署不会持久化该 Cookie。

---

## REST 端点

### `GET /api/status`

公开。UI 在加载时用它渲染头部并获取运行时样式配置（前缀文本与颜色）。

```json
{
  "ok": true,
  "service": "onlinechat",
  "registration": true,
  "onlineWeb": 3,
  "onlinePlayers": 7,
  "maxPlayers": 20,
  "motd": "A Minecraft Server",
  "twoFactor": false,
  "style": {
    "inGamePrefixText": "[In Game]",
    "inGamePrefixColor": "#2ecc71",
    "webPrefixText": "[Web Chat]",
    "webPrefixColor": "#e67e22",
    "maxMessageLength": 500,
    "minPasswordLength": 8
  }
}
```

### `POST /api/register`

公开（除非 `auth.allowRegistration = false`）。

请求：
```json
{ "username": "alice", "password": "hunter2hunter2" }
```

约束：
* `username` 必须匹配 `^[A-Za-z0-9_]{3,32}$` 且唯一（不区分大小写）。
* `password` 长度 ≥ `auth.minPasswordLength`。

响应 `200`（同时设置 `oc_token` Cookie）：
```json
{ "ok": true, "token": "…", "username": "alice" }
```

错误：`400`（校验失败）、`403`（注册被禁用）、`409`（用户名已被占用）。

### `POST /api/login`

请求：
```json
{ "username": "alice", "password": "hunter2hunter2" }
```

响应 `200`（同时设置 `oc_token` Cookie）：
```json
{
  "ok": true,
  "token": "…",
  "username": "alice",
  "bound": true,
  "mcName": "Steve",
  "mcUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5"
}
```

错误：`400`、`401`（凭据错误）、`429`（超出速率限制 —— 见
`auth.maxLoginAttempts` / `auth.loginCooldownSeconds`）。

### `POST /api/logout`

需要认证（Cookie 或请求头）。清除 `oc_token` Cookie，并关闭该账号的所有在线 WebSocket
会话，使浏览器停止接收推送。

响应：`{ "ok": true }`。

### `GET /api/me`

需要认证。返回调用者的账号记录。

```json
{
  "ok": true,
  "username": "alice",
  "bound": true,
  "mcName": "Steve",
  "mcUuid": "…",
  "mcOnline": true,
  "twoFactorAvailable": false,
  "twoFactor": false,
  "createdAt": 1710432000000,
  "lastLoginAt": 1710514800000
}
```

`twoFactorAvailable` 对应服务端配置中的 `twoFactor.enabled`；`twoFactor` 是该账号自身的开启状态。

### `POST /api/bind`

需要认证。请求绑定到指定名字的 Minecraft 玩家。该玩家 **必须在线**；系统会向其发送一个
可点击的确认提示。

请求：
```json
{ "mcName": "Steve" }
```

响应 `200`：
```json
{ "ok": true, "code": "X7K2QM", "expiresAt": 1710514920000, "mcName": "Steve" }
```

错误：
* `400` —— 玩家不在线、不存在、已绑定到另一个账号，或不允许重新绑定。响应体带有
  一个 `hint` 字段说明该怎么做。
* `401` —— 令牌缺失/无效。
* `503` —— Minecraft 服务器尚未完全启动。

此后客户端应等待 `bind_ok`、`bind_denied` 或 `bind_expired` 的 WebSocket 事件。

### `POST /api/unbind`

需要认证。移除调用者的绑定（并关闭该账号的 2FA，因为已没有需要保护的玩家）。无需请求体。

响应：`{ "ok": true }`。

### `POST /api/account/password`

需要认证。修改调用者的密码。

请求：
```json
{ "current": "hunter2hunter2", "next": "correct-horse-battery" }
```

响应 `200`：`{ "ok": true, "token": "…" }` —— 新令牌同时写入 `oc_token` Cookie。修改密码会推进
`lastLoginAt`，从而使 **所有** 已签发的令牌失效，并向该账号的全部在线 socket（包括调用者自己的）
推送 `force_logout` 帧（`reason: "password_changed"`）；响应中的新令牌使发起请求的浏览器保持登录。

错误：`400`（校验失败 / 太短）、`401`（当前密码错误）。

### `POST /api/account/delete`

需要认证。永久注销调用者的账号。

请求：
```json
{ "password": "hunter2hunter2" }
```

副作用：取消待处理的绑定请求、移除 Minecraft 绑定、释放该玩家当前被冻结的 2FA 会话（如有）、
向在线 socket 推送 `force_logout`（`reason: "account_deleted"`）并关闭，清除 Cookie。

响应：`{ "ok": true }`。错误：`401`（密码错误）。

### `POST /api/2fa/toggle`

需要认证。玩家侧开启/关闭进服两步验证。

请求：`{ "enabled": true }`
响应：`{ "ok": true, "twoFactor": true }`

错误：`403`（管理员未开放该功能）、`400`（未绑定玩家就尝试开启）。

### `GET /api/2fa/info?token=<token>`

公开（结果取决于调用者是否已登录）。返回确认页面渲染一个待处理 2FA 请求所需的全部信息：

```json
{
  "ok": true,
  "available": true,
  "valid": true,
  "authenticated": true,
  "username": "alice",
  "playerName": "Steve",
  "expiresAt": 1710514920000,
  "matches": true
}
```

* `available` —— 配置中的 `twoFactor.enabled`。
* `valid` —— 该 token 对应一位当前正被冻结等待的玩家。
* `authenticated` / `username` —— 请求是否携带了可用的 `oc_token` Cookie，以及对应谁。
* `matches` —— 当前登录账号正是绑定到该玩家的账号。只有此时 `/api/2fa/verify` 才会成功。

### `POST /api/2fa/verify`

需要认证。“是我本人”：放行 token 背后被冻结的玩家。

请求：`{ "token": "…" }`

| 状态 | `result` | 含义 |
|------|----------|------|
| `200` | `ok` | 玩家已放行。token 一次性，此后失效。 |
| `404` | `invalid_token` | token 未知、已使用或已过期。 |
| `403` | `wrong_account` | Cookie 属于其他账号 —— 玩家已被 **踢出**。 |
| `503` | `unavailable` | 服务器尚未就绪。 |

### `POST /api/2fa/reject`

需要认证。“不是我”：踢出 token 背后等待的玩家。

请求：`{ "token": "…" }` —— 响应：`{ "ok": true }`；token 不在待处理状态则返回 `404`。

### `GET /api/history`

需要认证。返回内存中的聊天历史（最多 `storage.chatHistorySize` 条消息，最旧的在前）。

```json
{
  "ok": true,
  "messages": [
    { "type": "chat", "ts": 1710514800000, "author": "Steve", "authorUuid": "…", "text": "hello" },
    { "type": "web",  "ts": 1710514801500, "author": "alice", "authorUuid": "…", "text": "hi" },
    { "type": "system", "ts": 1710514802000, "text": "Steve joined the game", "systemKind": "join" }
  ]
}
```

### `GET /api/online`

公开。列出游戏内玩家以及网页用户总数。

```json
{
  "ok": true,
  "players": [{ "name": "Steve", "uuid": "…" }],
  "webOnline": 3
}
```

### 静态文件

所有不在 `/api/` 或 `/ws` 下的路径都作为静态文件提供。每个请求 **先在磁盘上的 `storage.webDir`**
（默认 `config/onlinechat/web`，首次启动时从 jar 释放 —— 见 [CONFIGURATION.md](CONFIGURATION.md)）
中解析，找不到时回退到 jar 内 `/web/` 文件夹的副本。UI 是一个 **多页面** 应用：

| URL | 文件 | 用途 |
|-----|------|------|
| `/` | `web/index.html` | 落地页 —— 已登录则重定向到 `/chat.html`，否则显示登录入口。 |
| `/login.html` | `web/login.html` | 登录 / 注册（标签页切换）。接受 `?next=`（返回地址）与 `?reason=`（`kicked`、`password_changed`、`account_deleted`，决定到达时显示的提示）。 |
| `/account.html` | `web/account.html` | 账号页：Minecraft 绑定 + 实时确认、2FA 开关、修改密码、注销账号。 |
| `/bind.html` | — | 旧地址，`302` → `/account.html`。 |
| `/2fa/auth/<token>` | `web/2fa.html` | 游戏内聊天链接指向的两步验证确认页。`2fa.js` 从路径中读取 token。 |
| `/chat.html` | `web/chat.html` | 实时聊天桥接。 |
| `/style.css` | `web/style.css` | 共享样式表。 |
| `/common.js` | `web/common.js` | 共享库：API 辅助、i18n、toast、modal、导航、认证。 |
| `/index.js` `/login.js` `/account.js` `/2fa.js` `/chat.js` | `web/*.js` | 各页面逻辑。 |
| `/locales/en.json` `/locales/zh-CN.json` | `web/locales/*.json` | UI 翻译。 |

未知路径回退到 `index.html`。页面会自我守护：`account.html` 与 `chat.html` 在加载时调用
`GET /api/me`，当 Cookie 缺失或过期时重定向到 `/login.html?next=…`；`2fa.html` 通过 `/api/2fa/info`
做同样的事，因此用户登录后会回到确认页。

---

## WebSocket 协议（`wss://<host>/ws`）

所有帧都是 UTF-8 JSON 文本帧。二进制帧会被拒绝。

### 握手

1. 客户端连接到 `wss://<host>/ws`。浏览器会自动把 `oc_token` Cookie 附加到升级请求上。
2. 服务器发送：
   ```json
   { "type": "ready", "requiresAuth": true }
   ```
3. **如果握手携带了有效的 Cookie / Authorization 请求头**，服务器会立即认证并发送
   `auth_ok`（随后是 `history`），无需额外的帧。
4. 否则客户端 **必须** 在合理时间内发送 `auth`（连接有 120 秒的读空闲超时）：
   ```json
   { "type": "auth", "token": "…" }
   ```
5. 服务器回复 `auth_ok` 并紧接着发送一个 `history` 帧。

如果认证失败，服务器发送 `auth_error`，客户端应回退到登录界面。连接 **不会** 被自动关闭 ——
客户端可以用新的令牌再次尝试。

### 客户端 → 服务器

| `type` | 载荷 | 说明 |
|--------|------|------|
| `auth` | `{ token }` | 认证该连接。 |
| `chat` | `{ text }` | 发送一条聊天消息。需要认证。文本会被去除首尾空白、换行替换为空格、`§` 转义为 `&`，并截断到 `format.maxWebMessageLength`。 |
| `bind` | `{ mcName }` | 语义与 `POST /api/bind` 相同。 |
| `unbind` | — | 语义与 `POST /api/unbind` 相同。 |
| `history` | — | 重新请求消息历史。 |
| `ping` | — | 服务器回复带 `ts` 的 `pong`。在不暴露 WebSocket ping 帧的浏览器中可用于保活。 |

### 服务器 → 客户端

| `type` | 载荷 | 含义 |
|--------|------|------|
| `ready` | `{ requiresAuth:true }` | 握手已接受。 |
| `auth_ok` | `{ username, bound, mcName?, mcUuid? }` | 令牌已接受。 |
| `auth_error` | `{ error }` | 令牌被拒绝。 |
| `history` | `{ messages:[…] }` | 环形缓冲区的快照。 |
| `chat` | `{ ts, author, authorUuid, text }` | 来自 Minecraft 玩家的游戏内聊天。渲染时加 `[In Game]` 前缀。 |
| `web` | `{ ts, author, authorUuid?, text }` | 来自另一位网页用户的聊天（绝不回显给发送者本人）。渲染时加 `[Web Chat]` 前缀。 |
| `system` | `{ ts, text, systemKind }` | 加入 / 退出 / 死亡 / 进度 / 网页在线状态消息。`systemKind` ∈ `join, quit, death, advancement, web`。 |
| `bind_pending` | `{ code, mcName, expiresAt }` | 确认提示已发送给 MC 玩家。开始倒计时。 |
| `bind_ok` | `{ code, mcName }` | 玩家点击了 **[是]** —— 绑定现已存储。 |
| `bind_denied` | `{ code, mcName, reason }` | 玩家点击了 **[否]**。 |
| `bind_expired` | `{ code, mcName }` | TTL 到期而未确认。 |
| `bind_error` | `{ error }` | 无法发起绑定（玩家离线、不存在、已绑定……）。 |
| `unbind_ok` | — | 绑定已移除。 |
| `force_logout` | `{ reason }` | 该账号的令牌已被取代，服务器随后立即关闭 socket。`reason` ∈ `login_elsewhere, password_changed, account_deleted`。UI 会重定向到 `/login.html?reason=…`（自己发起的改密除外）。 |
| `server_shutdown` | — | Minecraft 服务器正在关闭。客户端显示阻塞式提示并停止重连。 |
| `pong` | `{ ts }` | 对 `ping` 的响应。 |
| `error` | `{ error }` | 针对格式错误的帧或未知 `type` 的通用错误。 |

### 会话示例

```
→ { "type":"auth", "token":"YWxpY2U.MTcxMDUxNDgwMDAwMA.abc…" }
← { "type":"auth_ok", "username":"alice", "bound":false }
← { "type":"history", "messages":[ … ] }
→ { "type":"bind", "mcName":"Steve" }
← { "type":"bind_pending", "code":"X7K2QM", "mcName":"Steve", "expiresAt":1710514920000 }
← { "type":"bind_ok", "code":"X7K2QM", "mcName":"Steve" }
→ { "type":"chat", "text":"hello from the browser" }
← { "type":"chat", "ts":…, "author":"Steve", "authorUuid":"…", "text":"welcome!" }
```

---

## 错误模型

REST 错误返回一个 JSON 体：
```json
{ "ok": false, "error": "Human readable message" }
```

WebSocket 错误使用上面列出的 `error` 或 `*_error` 帧；除非底层传输失败，否则连接保持打开。

---

## 速率限制

| 接入面 | 限制 | 可配置项 |
|--------|------|----------|
| 登录 | 在 `auth.loginCooldownSeconds` 内，每个 IP `auth.maxLoginAttempts` 次失败 | `onlinechat-server.toml` |
| WebSocket 帧大小 | 每个文本帧 64 KiB | 硬编码 |
| HTTP 体大小 | 1 MiB | 硬编码 |
| 聊天消息长度 | `format.maxWebMessageLength` | `onlinechat-common.toml` |
| WebSocket 空闲 | 120 秒内无任何入站帧 | 硬编码 |
