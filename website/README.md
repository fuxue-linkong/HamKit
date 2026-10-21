# HamKit 官网

HamKit 官网与内容管理后台。**零运行时依赖**（只用 Node 内置模块），首页、公告、下载、OIDC 登录、
管理后台、审计与统计全部自带，`npm install` 不是必需的。

```
website/
├── index.html            首页（原站点视觉 + 后端驱动的内容）
├── login.html            登录页（OIDC 引导 / 未配置时的部署说明 / 开发直登）
├── account.html          用户中心（资料、权限、会话、IdP 身份）
├── admin/index.html      管理后台（单页应用，hash 路由）
├── style.css             原首页样式（功能卡 / 技术栈 / 开发者）
├── public/               ← 通过 /assets/* 访问
│   ├── site.css          设计系统（颜色令牌 + 通用组件）
│   ├── admin.css         后台 / 登录 / 用户中心 专用样式
│   ├── site.js           前端核心：API 客户端、会话、DOM、格式化、提示、图表
│   ├── markdown.js       极简 Markdown 渲染（防 XSS、无依赖）
│   ├── home.js           首页交互（内容/版本/公告渲染、示波器背景）
│   ├── auth.js           登录页与用户中心逻辑
│   └── admin.js          管理后台逻辑（七个视图）
├── server/               后端
│   ├── index.js          启动入口（node server/index.js）
│   ├── config.mjs        配置加载 + .env 解析 + 自检
│   ├── core/             jwt / oidc / session / auth（纯逻辑，可测）
│   ├── store/            文件系统 JSON 存储 + 领域仓库
│   ├── http/             服务器、路由内核、响应与静态文件
│   ├── routes/           API 路由表、认证路由、App 兼容文件
│   └── dev/mock-oidc.mjs 内置 Mock 身份提供方（本地开发与测试）
├── test/                 自动化测试（node --test，离线可跑）
├── tools/verify-pages.mjs  页面级验收（CDP 真实渲染 + 截图）
└── data/                 运行时数据（版本/内容/公告/用户/审计/统计），自动创建
```

## 快速开始

```bash
cd website

# 1) 最省事的本地跑法：内置 Mock IdP + 开发直登
node server/index.js --mock-oidc
#   首页      http://localhost:8787/
#   登录      http://localhost:8787/login
#   后台      http://localhost:8787/admin/
#   Mock IdP  http://127.0.0.1:<随机端口>/.well-known/openid-configuration

# 2) 跑测试（不需要网络、不需要装依赖）
npm test

# 3) 页面级验收（需本机有 Chrome/Edge，会输出截图）
DEV_LOGIN=true node server/index.js &
node tools/verify-pages.mjs --base http://127.0.0.1:8787 --auth
```

访问 `/admin/` 时若未登录会自动跳转登录页，登录后回到 `/admin/`。
`--mock-oidc` 会同时打开 `DEV_LOGIN`，因此也可以直接访问
`/auth/dev-login?role=admin`（或 `editor` / `viewer`）免 IdP 登录。

## 接入你自己的 OIDC 身份提供方

所有参数都走环境变量，**不绑定任何厂商**。复制 `.env.example` 为 `.env` 后至少填三项：

```ini
BASE_URL=https://hamkit.click            # 必须是外网可访问的绝对地址，用于拼回调地址
OIDC_ISSUER=https://id.example.com/realms/hamkit
OIDC_CLIENT_ID=hamkit-website
OIDC_CLIENT_SECRET=****                  # 公共客户端（仅 PKCE）可留空
SESSION_SECRET=<48 位以上随机串>          # 生产环境必须显式配置
```

在 IdP 侧登记的**回调地址**是：

```
${BASE_URL}/auth/callback
```

登出回跳地址（若启用 RP-Initiated Logout）：

```
${BASE_URL}/
```

已验证的兼容项：

| 场景 | 配置 |
| --- | --- |
| Keycloak / Authentik / Casdoor 等 | 只配 `OIDC_ISSUER`，自动走 Discovery |
| IdP 不提供 Discovery | 配 `OIDC_AUTHORIZATION_ENDPOINT`、`OIDC_TOKEN_ENDPOINT`、`OIDC_JWKS_URI` |
| 要求 `client_secret_post` | `OIDC_CLIENT_AUTH_METHOD=client_secret_post` |
| 公共客户端（仅 PKCE） | 不配 `OIDC_CLIENT_SECRET`，自动使用 `none` |
| 用 ES256 / EdDSA 签发 ID Token | 默认已放行；可用 `OIDC_ALLOWED_ALGS` 收紧 |
| IdP 角色映射到本站角色 | `OIDC_ROLE_CLAIM=roles` + `OIDC_ROLE_MAP=hamkit-admin:admin,hamkit-editor:editor` |
| 限制可登录的邮箱域名 | `ALLOWED_EMAIL_DOMAINS=example.com` |
| 只允许指定人当管理员 | `BOOTSTRAP_ADMINS=alice@example.com,bob` |

### 角色与权限

三个角色，权限矩阵在 `server/core/session.mjs` 的 `PERMISSIONS` 中集中定义：

| 权限 | admin | editor | viewer |
| --- | :-: | :-: | :-: |
| 查看版本 / 内容 / 公告 / 统计 / 审计 | ✓ | ✓ | ✓ |
| 新增与修改版本、编辑内容、发布公告 | ✓ | ✓ | |
| 删除版本 | ✓ | | |
| 查看用户列表 | ✓ | ✓ | |
| 分配角色、停用用户、删除用户 | ✓ | | |
| 系统状态与在线会话管理 | ✓ | | |

角色来源优先级：**后台人工设置（锁定）> `BOOTSTRAP_ADMINS` > `OIDC_ROLE_MAP` > 库内既有角色 >
首个登录用户（`FIRST_USER_IS_ADMIN`）> `viewer`**。

系统会拒绝「降级 / 停用 / 删除最后一个管理员」，避免把自己锁在门外。

## 管理后台能做什么

| 视图 | 能力 |
| --- | --- |
| 概览 | 今日访问/下载/登录、近 30 天趋势图、站点状态、最近操作 |
| 版本管理 | 发布与管理版本（版本号、渠道、下载地址、SHA-256、更新日志），并**预览 App 将拉取到的 `update.json`** |
| 首页内容 | 首屏文案、功能卡（可增删/排序/启停）、下载区、页脚、开发者、SEO |
| 公告 | 发布/置顶/定时生效与失效，支持 Markdown |
| 用户 | 角色分配、停用（立即踢下线）、强制下线、删除 |
| 审计日志 | 全部写操作留痕，可按动作/关键字过滤、分页 |
| 系统状态 | 运行环境、OIDC 连通性探测、安全配置、在线会话、数据文件体积 |

## App 更新链路（向后兼容）

Android 端 `Downloader.kt` 固定请求 `https://hamkit.click/update.json`。本站把它变成
**由后台版本数据派生**的接口，因此后台一改，App 拉到的内容立刻同步：

```jsonc
// GET /update.json
{
  "versionCode": 16,
  "versionName": "2.2.0",
  "downloadUrl": "https://apk.hamkit.click/release/HamKit_2.2.0_16-release.apk",
  "changelog": "## 2.2.0 更新日志\n\n- ..."
}
```

同源还提供：

- `GET /version.json` —— `{ tag, prerelease, updated_at }`，首页版本徽标用
- `GET /update_<渠道>.json` —— `stable` / `beta` / `nightly` 分渠道更新文件
- `GET /api/public/latest`、`/api/public/versions` —— JSON 形式的版本列表
- `GET /api/public/download[?channel=beta]` —— 计一次下载后 302 到真实 APK

`update.json` 只反映 **stable** 渠道，避免测试版误推给所有用户。
版本列表是唯一事实来源（`data/versions.json`），上面这些文件都是它的投影，不会出现两处漂移。
若站点前面挂了 CDN 或对象存储而不经过 Node 服务，可用
`server/routes/compat.mjs` 的 `materializeCompatFiles()` 把派生文件落盘再上传。

## HTTP 接口

公开（无需登录）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 存活探针 |
| GET | `/api/meta` | 站点配置自检（含缺失项与 OIDC 提示） |
| GET | `/api/session` | 当前会话与权限 |
| GET | `/api/public/content` | 首页内容（仅启用中的功能卡） |
| GET | `/api/public/announcements` | 生效中的公告 |
| GET | `/api/public/versions` `/latest` | 已发布版本 |
| GET | `/api/public/download` | 计下载并跳转 APK |
| GET | `/update.json` `/version.json` `/update_<渠道>.json` | App 兼容文件 |
| GET | `/auth/login` `/auth/callback` `/auth/logout` `/auth/check` | OIDC 流程 |

需要会话（写操作还需要 `X-CSRF-Token` 头，前端会自动带上）：

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET / POST / PATCH / DELETE | `/api/versions[/:id]` | `version:read` / `version:write` / `version:delete` |
| GET / PATCH / POST | `/api/content`、`/api/content/reset` | `content:read` / `content:write` |
| GET / POST / PATCH / DELETE | `/api/announcements[/:id]` | `announcement:read` / `announcement:write` |
| GET / PATCH / DELETE | `/api/users[/:id]` | `user:read` / `user:write` |
| GET | `/api/audit` | `audit:read` |
| GET | `/api/stats`、`/api/stats/events` | `stats:read` |
| GET | `/api/system`、`POST /api/system/sessions/revoke` | `system:read` |
| GET | `/api/compat/preview` | `version:read` |

统一响应形态：成功 `{ ok: true, ... }`；失败 `{ ok: false, error, code }`。

## 部署

### 直接跑

```bash
NODE_ENV=production \
BASE_URL=https://hamkit.click \
OIDC_ISSUER=https://id.example.com \
OIDC_CLIENT_ID=hamkit-website \
OIDC_CLIENT_SECRET=**** \
SESSION_SECRET=$(head -c 48 /dev/urandom | base64) \
TRUST_PROXY=1 \
node server/index.js
```

### Nginx 反向代理

```nginx
server {
    listen 443 ssl http2;
    server_name hamkit.click;

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

`TRUST_PROXY=1` 表示信任一层代理，用于取真实客户端 IP（审计与统计）；不设则记录成代理地址。
HTTPS 部署时 `COOKIE_SECURE` 默认自动为 `true`，HSTS 也会自动开启。

### systemd

```ini
[Unit]
Description=HamKit Website
After=network.target

[Service]
WorkingDirectory=/srv/hamkit/website
EnvironmentFile=/srv/hamkit/website/.env
ExecStart=/usr/bin/node server/index.js
Restart=always
User=hamkit

[Install]
WantedBy=multi-user.target
```

### 数据与备份

运行时数据都在 `data/`（JSON / JSONL），直接打包即可备份：

```
data/
├── versions.json       版本列表（唯一事实来源）
├── content.json        首页文案与功能卡
├── announcements.json  公告
├── users.json          用户与角色
├── audit.json          审计日志（上限 5000 条）
├── sessions.json       会话登记表（支持强制下线）
├── stats.json          统计汇总
├── stats-YYYY-MM-DD.jsonl  按日分片的原始事件
└── .session-secret     未显式配置 SESSION_SECRET 时的落盘密钥（0600）
```

写入采用「临时文件 + rename」保证原子性；`data/` 已在 `.gitignore` 中忽略。

### 反向代理后注意

- `BASE_URL` 必须是外网访问的地址，否则 OIDC 回调地址会不匹配
- 公开读接口带 `ETag`，静态资源与 `/assets/*` 带缓存头，可直接被 CDN 缓存
- 关闭 CDN 对 `/api/*` 的缓存（写接口本就不缓存，但 `/api/public/*` 有短缓存）

## 安全说明

- **OIDC**：授权码 + PKCE(S256) + state + nonce；`state`/`nonce`/`code_verifier` 放在**签名过**的
  短期 HttpOnly Cookie 中，回调必须逐项校验；`iss`/`aud`/`azp`/`exp`/`nbf`/`iat` 全查
- **JWT**：默认拒绝 `alg=none` 与对称算法混杂；RSA/ECDSA/EdDSA 按 `kid` 或 RFC 7638 指纹选钥；
  JWKS 缓存 + 单飞 + 轮换刷新（带冷却，避免被伪造 `kid` 打爆 IdP）
- **会话**：HS256 签名 Cookie（HttpOnly + SameSite + Secure）+ 服务端登记表，支持即时撤销与踢人；
  停用用户会立即失效其全部会话
- **CSRF**：写操作要求 `X-CSRF-Token` 与 Cookie 双提交一致
- **开放重定向**：`returnTo` 只接受站内相对路径
- **路径穿越**：静态服务拒绝 `.env`、`data/`、`server/`、`package.json` 等，并校验解析结果落在根目录内
- **其他**：CSP、`nosniff`、`X-Frame-Options: DENY`、Referrer-Policy、登录端点独立限流、请求体上限 1MB
- **账号数据权利**：用户中心可导出本人资料/会话元数据/审计记录（`GET /api/me/export`），
  或输入 `DELETE` 注销账号（`DELETE /api/me`）；导出不包含会话 sid，注销会立即撤销全部会话，
  仓储层拒绝删除最后一个管理员
- **管理员防接管**：`FIRST_USER_IS_ADMIN` 默认 `false`；用 `BOOTSTRAP_ADMINS` 或
  `OIDC_ROLE_MAP` 指定管理员；建议在 IdP 开启 MFA 后设置 `OIDC_REQUIRE_MFA=true`，
  强制 admin/editor 登录带 `amr`/`acr` MFA 声明

> `DEV_LOGIN=true` 会开放无凭据登录，仅供本地调试；`NODE_ENV=production` 下会被强制忽略并告警。

## 测试

```bash
npm test          # 全部用例，离线可跑
npm run test:watch
```

覆盖范围：
- **OIDC 端到端**：内置 Mock IdP 走完整授权码 + PKCE 流程，校验回调、Cookie 属性、角色映射、
  state 篡改拒绝、登出撤销、开放重定向拦截
- **API 契约**：公开接口、版本 CRUD、内容与公告、用户与角色、审计与统计
- **App 兼容**：后台发布版本后 `update.json` / `version.json` / 分渠道文件随之变化，删除后正确回退
- **权限矩阵**：admin / editor / viewer 与未登录四种身份对各接口的访问结果
- **安全**：JWT 算法混淆、Cookie 伪造与篡改、路径穿越、敏感文件、安全响应头、限流

## 设计说明

保留原站点的视觉语言（午夜深空 + 大气蓝、玻璃卡片、示波器背景、鸿蒙字体优先），
把配色与组件抽成 `public/site.css` 的设计令牌层，首页、登录页、用户中心、后台共用一套。
页面不依赖任何构建步骤、CDN 或远程字体：直接改文件、刷新浏览器即可生效。

## 环境变量速查

见 `.env.example`。关键项：

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` / `HOST` | `8787` / `0.0.0.0` | 监听地址 |
| `BASE_URL` | `http://localhost:$PORT` | 对外地址，用于回调与 Cookie 安全判定 |
| `DATA_DIR` | `./data` | 数据目录 |
| `SESSION_SECRET` | 自动生成并落盘 | 生产环境必须显式配置 |
| `SESSION_TTL_HOURS` | `8` | 会话有效期 |
| `TRUST_PROXY` | `0` | 信任的代理层数 |
| `DEV_LOGIN` | `false` | 开发直登（生产环境自动忽略） |
| `FIRST_USER_IS_ADMIN` | `false` | 首个登录者是否自动成为管理员；生产环境建议保持 false |
| `BOOTSTRAP_ADMINS` | 空 | 直接授予管理员的邮箱/用户名，逗号分隔 |
| `ALLOWED_EMAIL_DOMAINS` | 空 | 允许登录的邮箱域名白名单 |
| `OIDC_REQUIRE_MFA` | `false` | 要求 admin/editor 的 OIDC 登录带 MFA 声明；IdP 配好 MFA 后开启 |
| `STATS_ENABLED` / `STATS_STORE_IP` | `true` / `false` | 统计开关 / 是否入库 IP |
| `STATS_RETENTION_DAYS` | `180` | 统计保留天数 |
| `RATE_LIMIT_MAX` / `LOGIN_RATE_LIMIT_MAX` | `240` / `30` | 每分钟限流阈值 |

## 许可证

与主项目一致：[MIT License](../LICENSE)
