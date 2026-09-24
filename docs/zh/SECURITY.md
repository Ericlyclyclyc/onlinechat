# 安全

> 语言：[English](../SECURITY.md) | **简体中文**

本文档描述 Online Chat 的威胁模型、内置的缓解措施，以及在把平台暴露到公网之前
你应当逐项执行的检查清单。

---

## 威胁模型

| 资产 | 攻击者目标 | 缓解措施 |
|------|-----------|----------|
| TLS 私钥（`./ssl/privkey.pem`） | 冒充服务器、对聊天进行中间人攻击 | 文件仅存在于主机上，永不离开。权限应为 `600`。可通过 `tls.privateKeyPassword` 支持可选口令。 |
| 网页账号密码 | 撞库、账号接管 | PBKDF2-HMAC-SHA256，每账号随机 16 字节盐，可配置迭代次数（默认 210 000），常量时间比较。明文永不写入磁盘或日志。 |
| 认证令牌 | 会话劫持 | HMAC-SHA256 签名，内嵌过期时间，密钥由 48 字节 `SecureRandom` 自动生成并存储在账号文件旁。轮换密钥会使所有令牌失效。签发时间早于账号 `lastLoginAt` 的令牌会被拒绝，因此修改密码（网页或 `/onlinechat account setpassword`）会让其它所有设备下线。 |
| Minecraft ⇄ 网页绑定 | 在网页聊天中冒充某玩家 | 要求 MC 玩家 **在线** 并在游戏内点击 `[是]` 按钮（该按钮运行 `/onlinechat bind confirm <code>`）。验证码为 6 个字符、取自无歧义字母表、受 TTL 限制且一次性使用。`confirm()` 还会校验 **点击玩家的 UUID** 与待处理请求是否匹配，因此无人能确认一个针对其他玩家的绑定。绑定请求按网页账号限流（`limits.bindRequestsPerMinute`）。 |
| 被盗 / 被破解的 Minecraft 账号进服 | 以合法玩家身份游戏和聊天 | 可选的 **进服 2FA**（`[twoFactor]`，玩家自行选择开启）。玩家进服时被冻结 —— 移动/交互距离属性归零，交互/攻击/物品/丢弃/聊天/命令事件被取消 —— 并收到链接 `publicUrl/2fa/auth/<token>`。Token 为 32 字节 `SecureRandom`（base64url）、一次性、与进服 UUID 绑定，并在 `timeoutSeconds` 后过期。确认页仅在浏览器 `oc_token` cookie 属于 **绑定到这个玩家** 的账号时才放行；其它账号、显式点「不是我」、或超时均会踢出玩家。Token 从不写入磁盘或日志。 |
| 聊天注入 | 向游戏发送带 `§` 格式或类似命令的文本 | 网页侧文本在广播前会把 `§` 替换为 `&`、剥离换行并限制长度。网页消息通过 `broadcastSystemMessage` 广播，它 **不会** 触发 `ServerChatEvent`，因此命令前缀（`/…`）永远不会被执行。 |
| 经网页 UI 的存储型 XSS | 利用精心构造的玩家名 / 消息在其他用户浏览器中运行脚本 | 每个作者名与消息体在渲染前都会在客户端做 HTML 转义。所提供页面还携带严格的 `Content-Security-Policy`（`script-src 'self'`，无内联脚本），外加 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`，以及在 TLS 上的 HSTS。 |
| 拒绝服务 | 耗尽服务器资源 | 按 IP **及** 全局的 WebSocket 连接上限、按 IP 的登录/注册限制、按会话的聊天消息限制、按账号的绑定限制（全部在连接之间共享，并按 IP/账号而非 TCP 连接为键）。HTTP 体上限 1 MiB，WebSocket 帧上限 64 KiB，120 秒空闲超时，聊天历史受环形缓冲区约束。PBKDF2 哈希与账号文件写入被卸载到一个有界工作线程池，因此绝不会阻塞 Netty 事件循环。 |
| 跨源滥用 / CSWSH | 来自恶意网站的随手调用或跨站 WebSocket 劫持 | 单一来源白名单（`cors.allowedOrigins`）同时治理 REST（通过 CORS 头）与 WebSocket 升级（通过握手前的 `Origin` 校验）。`oc_token` 认证 Cookie 为 `HttpOnly`、`SameSite=Lax`，并在 TLS 监听器上带 `Secure`。 |
| 聊天历史重放 | 泄露私密对话 | 历史只发送给已认证的会话。 |

---

## 静态数据（落盘）

模组写入以下文件（位置可配置）：

| 文件 | 内容 | 敏感度 |
|------|------|--------|
| `onlinechat/accounts.json` | 用户名、PBKDF2 哈希、盐、迭代次数、绑定的 MC UUID + 名字、2FA 标志、时间戳 | 中 —— 泄露后可对密码进行离线暴力破解。 |
| `onlinechat/accounts.json.bak` | 上一份完好账号集的一代备份 | 同上。 |
| `onlinechat/token.secret` | 48 字节 HMAC 密钥（Base64-URL） | 高 —— 泄露后攻击者可为任意用户伪造令牌。 |
| `onlinechat/chat_history.jsonl` | 追加式聊天归档（作者、文本、时间戳） | 中 —— 私密对话。 |
| `config/onlinechat/web/` | 网页前端的释放副本（HTML/JS/CSS/locales）+ 隐藏的 `.exist` 标记 | 作为数据低，**作为攻击面高**：能写这里的人可以向所有用户注入脚本。保持由服务器用户拥有，不要全局可写。 |
| `config/onlinechat-server.toml` | 端口、TLS 路径、可选的 `tokenSecret` 覆盖、可选的私钥口令 | 如果你在此设置了口令或固定密钥，则敏感度为高。 |

账号与密钥文件以原子方式写入（临时文件 + `ATOMIC_MOVE`），并且在 POSIX 文件系统上会自动
限制为仅属主可读写（`0600`）—— 包括 `.tmp` 与 `.bak` 兄弟文件。启动时，损坏的
`accounts.json` 会触发从 `accounts.json.bak` 自动恢复；如果两者都不可用，则文件被原样保留
以供手动恢复，而不会被空集覆盖。在非 POSIX 文件系统（Windows NTFS）上，权限步骤是静默的
no-op，因此请改用 NTFS ACL 保护这些文件。

聊天消息保存在内存环（`storage.chatHistorySize`）中，并追加到 `storage.chatLogFile` 以供分页；
如不需要归档，删除该文件即可。

注销网页账号（账号页自助，或 `/onlinechat account delete`）会删除其记录、玩家绑定、待处理的
绑定请求与被冻结的 2FA 会话，并强制关闭其在线连接。`.bak` 文件在下次保存前仍可能保留上一代数据。

`./ssl/` 中的 TLS 材料除在服务器启动时被 `SslContextBuilder` 读取外，绝不会被任何其他东西
读取；它永不被传输、记录或嵌入模组 jar。

---

## 加固检查清单（公开部署）

* [ ] 使用来自真实 CA 的证书（Let's Encrypt 免费）。自签名证书会训练用户去点穿警告。
* [ ] `chmod 600 ./ssl/privkey.pem`（模组已在 POSIX 上自动为 `accounts.json` 与
      `token.secret` 设置 `0600`；TLS 私钥则由你自行保护）。
* [ ] 把这些文件 `chown` 给运行 Minecraft 服务器的用户，而非 root。
* [ ] 让 `./ssl/` 与 `./onlinechat/` 远离版本控制 —— 它们已列在 `.gitignore` 中；切勿强制添加。
* [ ] 一旦初始用户完成注册，就设置 `auth.allowRegistration = false`，或把注册置于反向代理之后。
* [ ] 若 CPU 预算允许，把 `auth.pbkdf2Iterations` 提高到 600 000（OWASP 2023）。
* [ ] 对更高安全性的部署，把 `auth.tokenTtlMinutes` 降低到 60–240。浏览器把会话保存在
      `oc_token` Cookie 中（前端还会把它镜像到 `localStorage`），因此更短的 TTL 只是迫使
      更频繁地重新登录。
* [ ] 把 `cors.allowedOrigins` 限制为你实际的前端来源，而不是 `*`。这现在也会锁定
      WebSocket 升级（`Origin` 校验），从而封堵跨站 WebSocket 劫持。
* [ ] 如果你需要 HTTP/2、IP 白名单、WAF 规则或集中式日志，把服务器置于反向代理
      （nginx、Caddy、Traefik）之后。在代理处终止 TLS 并转发到 `127.0.0.1:<port>` ——
      在 `onlinechat-server.toml` 中设置 `host = "127.0.0.1"`，使 Netty 监听器不直接暴露。
* [ ] 启用主机防火墙，且只向公网开放 HTTPS 端口。
* [ ] 生产环境不要启用 `verboseLogging` —— 它会打印 IP 与用户名。
* [ ] 如果怀疑密钥泄露，轮换 `token.secret`（删除该文件并重启）。
* [ ] 备份 `onlinechat/accounts.json` —— 丢失它意味着每个用户都必须重新注册并重新绑定。
      模组保留一个滚动的 `accounts.json.bak`，但那只是一代安全网，不能替代真正的备份。
* [ ] 根据你预期的负载调优 `[limits]` 块（每 IP / 总连接数、每小时注册数、每分钟聊天
      消息数、每分钟绑定请求数）。
* [ ] 如果开启 `[twoFactor]`，把 `publicUrl` 设为玩家实际使用的那个 HTTPS 来源；来源不对会导致
      cookie 不被发送，所有 2FA 进服都会失败。保持 `timeoutSeconds` 较短（默认 120 秒）——
      被冻结的玩家仍占用一个玩家位。
* [ ] 限制对 `storage.webDir`（`config/onlinechat/web`）的写权限。那里的文件会原样提供给每个
      浏览器；请把它当作你的 web 根目录来对待。

---

## 本模组 **不** 做的事

* 网页登录 **本身** 没有 TOTP / 验证器 App 式的 2FA。内置的 2FA 保护的是 *Minecraft 进服*，以
  网页会话作为第二因素；如果网页账号也需要第二因素，请用反向代理在 API 前面加上。
* 无邮箱验证或密码重置。设计上就没有邮件通道 —— 模组从不发起出站网络调用。用户在账号页
  自行修改密码；管理员可用 `/onlinechat account setpassword` 重置。
* 无逐条消息审核、无禁言、无封禁。请用现有的 Minecraft 服务器工具处理滥用
      （封禁 MC 账号；网页绑定随之失效）。
* 无消息编辑或删除。历史是一个固定的环形缓冲区加一份追加式日志。
* 无与其他服务器或平台（Discord、Matrix……）的联邦互联。桥接严格限于
      Minecraft ↔ 自带网页 UI。
* 无客户端模组要求。一切都能在原版客户端上工作。

---

## 报告问题

如果你发现安全漏洞，请 **不要** 开公开的 issue。请私下联系维护者
（见你所用分发渠道上的模组页面），并附上：

* 一个最小复现
* 受影响的版本（`gradle.properties` 中的 `mod_version`）
* 该漏洞是可远程利用，还是需要一个已认证的账号

我们的目标是：对严重问题在 72 小时内确认、14 天内发布修复。
