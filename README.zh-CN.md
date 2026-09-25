# Online Chat —— Minecraft ⇄ 网页聊天桥接（NeoForge 1.21.1）

> 语言：[English](README.md) | **简体中文**

一个内置于 Minecraft 服务器的、自包含的 **HTTPS + WebSocket** 聊天平台。
网页用户可以注册、登录，通过可点击的 **[是] / [否]** 确认把网页账号与游戏内账号绑定，
随后与玩家实时聊天。

* 🔐 TLS 由你自己的 PEM 证书材料提供（默认目录 `./ssl`，目录可配置）—— 数据永不离开你的机器。
* 🧑‍🤝‍🧑 双向桥接：游戏内聊天显示到网页，网页聊天显示到游戏内。
* 🎨 区分前缀：网页上显示绿色 `[In Game]`，游戏内显示橙色 `[Web Chat]`。
* 📢 可选地桥接非玩家消息（加入、退出、死亡、进度）。
* 🔍 网页端支持**全文搜索聊天归档**，并带 @提及 自动补全与高亮。
* 📣 `/onlinechat announce` 将管理员公告同时广播到游戏内与网页端。
* ⚙️ 拆分的配置文件（`onlinechat-common.toml` + `onlinechat-server.toml`）。
* 🛡️ 可选的 **进服两步验证（2FA）**：开启的玩家进服后被冻结，直到在已登录其绑定网页账号的浏览器中确认。
* 🌐 所有游戏内文本在 **服务端** 翻译（`language = "en_us" | "zh_cn"`）—— 原版客户端也能看到中文。
* 🧩 网页前端首次启动时释放到 `config/onlinechat/web/`，无需重新构建 jar 即可自定义。
* 🚫 零额外运行时依赖 —— Netty 与 Gson 均由 Minecraft 自身提供。

---

## 目录

| 文档 | 用途 |
|------|------|
| [README.zh-CN.md](README.zh-CN.md) *（本文件）* | 概览、功能列表、快速开始 |
| [docs/zh/INSTALL.md](docs/zh/INSTALL.md) | 构建模组、部署、TLS 配置 |
| [docs/zh/CONFIGURATION.md](docs/zh/CONFIGURATION.md) | 两个配置文件中每一项选项的详解 |
| [docs/zh/WEB_API.md](docs/zh/WEB_API.md) | REST 端点与 WebSocket 协议参考 |
| [docs/zh/SECURITY.md](docs/zh/SECURITY.md) | 威胁模型、加固检查清单、数据存储 |
| [docs/zh/DEVELOPMENT.md](docs/zh/DEVELOPMENT.md) | 项目结构与模组扩展方式 |

> 英文版文档见 [README.md](README.md) 及 `docs/` 目录。

---

## 快速开始（5 分钟）

1. **把你的 TLS 材料放入** `./ssl/`：
   ```
   ssl/
   ├── fullchain.pem   # 证书 + 中间证书链
   ├── privkey.pem     # PKCS#8（或 PKCS#1）私钥，可带密码保护
   └── cert.pem        # 可选，默认不使用
   ```
2. **构建模组**：
   ```powershell
   .\gradlew.bat build
   ```
   jar 会输出到 `build/libs/onlinechat-1.21.1-neoforge-0.0.3-alpha.jar`。
3. **安装** 到你的 `mods/` 文件夹（服务端和/或客户端 —— Web 服务器只在逻辑服务端一侧启动）。
4. **启动 Minecraft**（专用服务器，或开放到局域网的单人世界 —— 两者皆可）。
   Web 服务器默认监听 `https://0.0.0.0:8443/`。
5. 在浏览器中 **打开** `https://<你的主机>:8443/`，注册、登录。
6. **绑定**：打开 **账号** 页，在 *绑定 Minecraft 玩家* 面板中输入你的 Minecraft 用户名。
   游戏内你会看到：
   > **[OnlineChat]** 网页用户 *alice* 想要绑定到你的 Minecraft 账号。*（验证码：X7K2QM）*
   > **[是] [否]**

   点击 **[是]**。就这样 —— 你现在可以从浏览器与服务器对话了。
7. *（可选）* 在 `onlinechat-server.toml` 中设置 `twoFactor.enabled = true` 与 `twoFactor.publicUrl`；
   之后玩家就能在自己的账号页打开 **两步验证** 开关。

---

## 游戏内效果

| 情形 | 游戏内聊天行 |
|------|--------------|
| 网页用户 *alice*（绑定到 *Steve*）说 "hi" | <span style="color:#e67e22">**[Web Chat]**</span> `<Steve> hi` |
| 玩家 *Steve* 说 "hello" | 原版 `<Steve> hello` |
| 网页用户 *bob* 连接到 WebSocket | `[OnlineChat] bob connected to the web chat` |
| 绑定确认 | `[OnlineChat] 网页用户 'alice' 已绑定到 Steve。`（`language = "zh_cn"` 时） |
| *Steve* 在开启 2FA 的情况下进服 | `[OnlineChat] 你的账户已开启两步验证。请在已登录网页账户的浏览器中打开以下链接：` + 可点击的 `https://…/2fa/auth/…`（确认前被冻结） |

前缀文本、颜色以及整行格式均可配置 —— 见
[docs/zh/CONFIGURATION.md](docs/zh/CONFIGURATION.md#前缀)。

## 网页端效果

| 情形 | 网页视图 |
|------|----------|
| 玩家 *Steve* 说 "hello" | <span style="color:#2ecc71">**[In Game]**</span> **Steve** hello |
| 另一位网页用户 *bob* 说 "yo" | <span style="color:#e67e22">**[Web Chat]**</span> **bob** yo |
| *Steve* 死了 | `Steve fell from a high place`（系统消息，灰色斜体） |
| *Steve* 加入了游戏 | `Steve joined the game`（系统消息） |

---

## v0.0.2+ —— 修复 0.0.1 的服务端启动崩溃

`0.0.1-alpha` 在 **抽象类** `PlayerInteractEvent` 上注册了监听器，导致 NeoForge（21.1.233+）在
`ServerStarting` 阶段中止并报错：

> `Cannot register listeners for abstract class net.neoforged.neoforge.event.entity.player.PlayerInteractEvent`

`0.0.2-alpha` 及更高版本改为注册四个具体的交互子类（`RightClickBlock` / `RightClickItem` /
`EntityInteract` / `LeftClickBlock`），2FA 冻结依然能拦截所有交互，但不会再让服务器崩溃。如果你遇到
该报错，把 jar 换成 `onlinechat-1.21.1-neoforge-0.0.3-alpha.jar` 即可，无需迁移任何配置或数据。
这些版本还新增了归档搜索、公告以及上面列出的网页聊天体验改进。

---

## 功能清单

| 功能 | 状态 |
|------|------|
| 网页登录 / 注册（PBKDF2 哈希） | ✅ |
| HMAC 签名的无状态令牌 | ✅ |
| 基于 Cookie 的会话（`oc_token`，HttpOnly/Secure/SameSite） | ✅ |
| 网页账号 ⇄ Minecraft 玩家绑定，可点击 **[是]** 确认 | ✅ |
| 绑定要求 MC 玩家 **在线** | ✅ |
| 由本地 `./ssl` PEM 材料提供 HTTPS —— 永不离开主机 | ✅ |
| 双向聊天桥接 | ✅ |
| 游戏内为网页发送者显示橙色 `[Web Chat]` 前缀 | ✅ |
| 网页上为游戏发送者显示绿色 `[In Game]` 前缀 | ✅ |
| 可配置的非玩家消息桥接（加入/退出/死亡/进度） | ✅ |
| 拆分配置（`common` + `server` TOML 文件） | ✅ |
| 独立登录 / 账号 / 聊天页面 | ✅ |
| 账号页：绑定、修改密码、注销账号（自动解绑） | ✅ |
| 账号页：注册时间 / 最近登录信息，UUID 一键复制 | ✅ |
| 可选的玩家级 **进服 2FA**（浏览器确认，超时踢出） | ✅ |
| 管理员命令 `/onlinechat account setpassword\|delete` | ✅ |
| `/onlinechat announce <文本>` —— 公告同时发送到游戏与网页 | ✅ |
| `/onlinechat webusers` —— 查看当前在线的网页用户 | ✅ |
| 双语 UI + **服务端** 游戏内文本翻译（English & 简体中文） | ✅ |
| 网页前端释放到 `config/onlinechat/web/` 供自定义 | ✅ |
| WebSocket 连接时重放聊天历史 + 分页归档 | ✅ |
| 聊天归档全文搜索（`GET /api/search`）+ 聊天页搜索面板 | ✅ |
| 网页聊天体验：日期分隔、消息分组、链接可点击、@提及高亮与自动补全、复制按钮、草稿恢复、标题未读角标、可选提示音、密码可见性切换 | ✅ |
| WebSocket 跨页面共享（SharedWorker）+ 平滑换页过渡 —— 在聊天 ⇄ 账号页面之间切换不会断连，也不会刷屏连接/断开消息 | ✅ |
| 按 IP 的登录限流 | ✅ |
| CORS 白名单 | ✅ |
| 零外部运行时依赖（Netty 与 Gson 来自 Minecraft） | ✅ |

---

## 环境要求

* Minecraft **1.21.1**
* NeoForge **21.1.233** 或更新
* Java **21**
* 一份 TLS 证书（自签名证书适用于局域网测试，公开暴露请用 Let's Encrypt）

---

## 文件位置

```
./ssl/                                    # TLS 材料（输入）
./config/onlinechat-common.toml           # 聊天桥接设置（首次运行时创建）
./config/onlinechat-server.toml           # HTTPS + 认证 + 存储 + 2FA 设置（首次运行时创建）
./config/onlinechat/web/                  # 可编辑的网页 UI 副本 + 隐藏的 .exist 标记（首次运行时创建）
./onlinechat/accounts.json                # 网页账号、绑定、2FA 标志（首次运行时创建）
./onlinechat/token.secret                 # 自动生成的 HMAC 密钥（首次运行时创建）
./onlinechat/chat_history.jsonl           # 追加式聊天归档（首次运行时创建）
```

切勿把 `./ssl`、`./onlinechat/accounts.json` 或 `./onlinechat/token.secret` 提交到版本控制。
模板已提供针对 `ssl/` 的 `.gitignore` 规则。

---

## 许可

底层 NeoForge MDK 模板的许可见 [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt)。
本仓库中的 Online Chat 源码按现状（as-is）提供，供个人及商业服务器使用。
