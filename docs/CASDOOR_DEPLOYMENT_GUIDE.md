# Casdoor 部署与运行指南（实测 + 一手来源核对）

本文回答两件事：**Casdoor 怎么跑起来**、**怎么接给 HamKit 官网当 OIDC 登录源**。

> **实测环境**：Windows + Docker Desktop（Docker 29.8.0 / Compose v5.5.1），
> 镜像 `casbin/casdoor:latest`（= **v4.13.0**，digest `sha256:758bb52a…`，225 MB，linux/amd64）
> + `mysql:8.0`。文中标注「**实测**」的内容是本机真实跑出来的结果，其余为上表来源核对。
>
> 来源核对详见配套笔记 [`casdoor-deployment-research.md`](./casdoor-deployment-research.md)（源码 commit `c9adca03`、
> 官方文档、Docker Hub），本文只保留可执行结论。

---

## 1. 最快跑起来（含数据库，推荐）

Casdoor 默认走 **MySQL**（`driverName = mysql`、`dataSourceName = root:123456@tcp(localhost:3306)/`、`dbName = casdoor`），
所以单机部署最省事的是「Casdoor + MySQL」两个容器。

`docker-compose.yml`（**实测可直接用**）：

```yaml
services:
  casdoor-db:
    image: mysql:8.0
    container_name: casdoor-db
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: 123456      # 生产请改成强密码
      MYSQL_DATABASE: casdoor
      TZ: Asia/Shanghai
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
    volumes:
      - casdoor-db-data:/var/lib/mysql
    healthcheck:
      test: ["CMD-SHELL", "mysqladmin ping -h 127.0.0.1 -uroot -p$$MYSQL_ROOT_PASSWORD --silent"]
      interval: 5s
      timeout: 3s
      retries: 30
      start_period: 30s

  casdoor:
    image: casbin/casdoor:latest
    container_name: casdoor
    restart: unless-stopped
    depends_on:
      casdoor-db:
        condition: service_healthy     # 等数据库真正就绪，避免启动 panic
    ports:
      - "8000:8000"
    volumes:
      - ./conf/app.conf:/conf/app.conf  # 关键：覆盖镜像内置配置
      - casdoor-logs:/logs
      - ./files:/files                  # 上传文件持久化（镜像未声明 VOLUME）
    environment:
      TZ: Asia/Shanghai

volumes:
  casdoor-db-data:
  casdoor-logs:
```

`conf/app.conf`（**实测最小必要改动**）：

```ini
appname = casdoor
httpport = 8000
runmode = dev
driverName = mysql
dataSourceName = root:123456@tcp(casdoor-db:3306)/   # 注意：这里必须是 compose 的服务名
dbName = casdoor
defaultLanguage = "zh"
logConfig = {"adapter":"file", "filename": "logs/casdoor.log", "maxdays":99999, "perm":"0770"}
```

> ⚠️ **镜像内置的 `app.conf` 写的是 `localhost:3306`**（实测从镜像里 dump 出来的原文），
> 容器化时 Casdoor 与 MySQL 分属不同容器，**必须把 `dataSourceName` 的主机改成服务名**，
> 否则 Casdoor 连不上库并在启动时直接失败。

启动与验证：

```bash
docker compose up -d

# 就绪探测（实测：首次约 10–15 秒，重启后约 5 秒）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8000/.well-known/openid-configuration
# => 200

docker compose logs -f casdoor        # 官方推荐的看日志方式
```

**实测** 容器状态与监听：

```
casdoor      Up  0.0.0.0:8000->8000/tcp     # 同时监听 389（内置 LDAP）
casdoor-db   Up (healthy)  3306/tcp
# 容器内进程：PID 1 = /server，用户 casdoor(uid 1000)，非 root
```

### 首次启动会自动做什么（实测确认）

* **自动建表 + 写入内置数据**：内置组织 `built-in`、内置应用 `app-built-in`、内置管理员用户、
  内置证书 `cert-built-in`。**没有任何手工初始化命令**（数据库迁移也靠这个机制，每次启动自动执行）。
  → 因此**升级前必须备份数据库**。
* 默认管理员：**`built-in/admin` / `123`**。
  **实测** 用它成功登录控制台（`/api/login` 返回 `ok`，`get-captcha` 返回 `type: "none"`，
  即默认无需图形验证码，脚本/自动化可直登）。

> 🔴 **公网暴露前第一件事就是改掉 `123`。** 这是源码里的字面量默认值，不是随机生成。

### 更省事的临时方案（不要用于生产）

```bash
docker run -p 8000:8000 casbin/casdoor-all-in-one
```

`casbin/casdoor-all-in-one` 是**独立镜像**，内置 SQLite，官方明确标注 *"Quick try-out only; not for production"*。

---

## 2. 访问控制台

浏览器打开 `http://<host>:8000/`，用 `admin` / `123` 登录，界面语言默认英文，可切中文。

**实测** 控制台与 OIDC 端点同进程同端口；`/login` 会返回 Casdoor 自己的 SPA 登录页。

---

## 3. 接给 HamKit：完整配置

设 Casdoor 对外地址 `https://id.hamkit.click`，HamKit 官网 `https://hamkit.click`。

### 3.1 Casdoor 侧（四步）

| 步骤 | 位置 | 做什么 |
| --- | --- | --- |
| ① 设对外 origin | `conf/app.conf` | `origin = https://id.hamkit.click`（**必做**，见 3.4） |
| ② 建应用 | 控制台 → Applications → 新建/编辑 | 记下 **Client ID / Client Secret** |
| ③ 登记回调 | 同页 → **Redirect URLs** | 加 `https://hamkit.click/auth/callback` |
| ④ 改 Token 形态 | 同页 → Token 格式/Token 属性 | **`JWT-Custom`** + 属性 `roles` ← `Existing Field` = `Roles`（**必做**，见 3.5） |

补充要点：

* **Redirect URL 的校验规则**（源码 `IsRedirectUriValid()`）：**路径必须完全相等、不支持通配**；
  scheme 与端口必须一致（loopback 例外）；主机支持子域后缀匹配。
  所以本地调试地址 `http://localhost:8787/auth/callback` **必须单独再加一条精确项**（`http`/`https` 不互通）。
* **别填错地方**：Provider 里的 callback 是 *Casdoor 自己*的 `/callback`；这里要填的是应用里的 Redirect URL。
* **登出回跳复用同一份列表**：若 HamKit 传 `post_logout_redirect_uri`，该地址也必须登记在这里，否则 Casdoor 报
  `Redirect URI ... doesn't exist in the allowed Redirect URI list`。
* 角色：在控制台建 Role（如 `hamkit-admin` / `hamkit-editor` / `hamkit-viewer`）并把用户加入对应 Role。
* ⚠️ **`built-in` 组织不能新增普通用户**（实测报错：*「向 'built-in' 组织添加新用户的功能已禁用…该组织所有用户均为全局管理员」*）。
  要放普通用户，**必须新建一个组织**，并把应用挂到该组织下。
  （另一个实测坑：新建的组织若还没有属于它的应用，`add-user` 会报 *「组织: admin 应该拥有至少一个应用」*——
  顺序上先让组织有应用，再往里加用户。控制台里按 ②→新组织→建用户 的正常顺序做不会踩到。）

### 3.2 HamKit 侧 `.env`

```dotenv
BASE_URL=https://hamkit.click
OIDC_ISSUER=https://id.hamkit.click          # 见 3.4：不带 /app-xxx、不带尾斜杠、不带 /.well-known
OIDC_CLIENT_ID=<Casdoor 应用 Client ID>       # Casdoor 允许自定义语义化 clientId（实测可行）
OIDC_CLIENT_SECRET=<Casdoor 应用 Client Secret>
OIDC_CLIENT_AUTH_METHOD=client_secret_basic   # Casdoor 三种都支持，basic 最通用
OIDC_SCOPE=openid profile email               # profile 必须保留，见 3.5
OIDC_ROLE_CLAIM=roles
OIDC_ROLE_MAP=hamkit-admin:admin,hamkit-editor:editor,hamkit-viewer:viewer
TRUST_PROXY=1
```

### 3.3 验证顺序

```bash
# 1) issuer 必须与 OIDC_ISSUER 逐字符相等
curl -s https://id.hamkit.click/.well-known/openid-configuration | grep issuer

# 2) 走一遍浏览器登录，然后看会话里的 idpRoles
curl -s -b cookies.txt http://127.0.0.1:8787/api/session | grep -o '"idpRoles":[^]]*]'
```

`idpRoles` 为空但 Casdoor 里确实给用户配了角色 → 说明第 ④ 步的 Token 形态没改对，回到 3.5。

**实测**：本机以 `http://127.0.0.1:8000` 为 issuer、`client_secret_post`、回调
`http://127.0.0.1:8899/auth/callback` 跑通了 **HamKit → Casdoor 登录页 → 填账号 → 回调 → 直接进入
`/admin/#/overview` 且已登录** 的完整链路，无需任何 HamKit 代码改动。

### 3.4 关于 issuer（最容易配错的一项）

**`OIDC_ISSUER` = `https://id.hamkit.click`**，即 Casdoor 的后端 origin 本身：

* **不含** `/<appname>`，**不含** `/.well-known/...`，**不带尾斜杠**。
* 官方文档专门说明这是**有意为之**：`iss` claim 永远是后端 host，且**旧版本**曾返回应用级 issuer
  （`https://host/.well-known/app-example`）导致严格客户端校验失败。
* 源码 `GetOidcDiscovery()` 里，全局分支与应用级分支的 `issuer` **都是 `originBackend`**，
  只有 `jwks_uri` 才是应用级。
* `originBackend` 推导优先级：`app.conf` 的 `origin` 非空 → **直接用 `origin`**；否则按请求 `Host` 头推 scheme
  （Host 不含 `.` 或是 IP → `http://`，否则 `https://`）。
  `originFrontend` 只影响 `authorization_endpoint`，**不影响 issuer**。
* **实践含义**：走反代而 `origin` 留空时，issuer 会随 `Host` 头变化；IP 换成域名就会导致
  issuer 变化、旧 token 全部校验失败、且 HamKit 侧 `OIDC_ISSUER` 对不上。**生产必须显式设 `origin`。**
* 顺带一提：Casdoor 的 `sub` 是 `user.Id`（UUID），**不是用户名**。

### 3.5 关于角色 claim（第二容易踩的坑）

**结论：Casdoor 默认 TokenFormat=`JWT` 时，ID Token 里的 `roles` 是「Role 对象数组」，不是字符串数组。**

| TokenFormat | ID Token 的 `roles` | 形态 |
| --- | --- | --- |
| **`JWT`（默认）** | 存在 | **对象数组**（owner/name/displayName/…），不是 `["admin"]` |
| `JWT-Empty` | 不存在 | 仅极少数字段 |
| `JWT-Standard` | 不存在 | 标准 OIDC claim |
| **`JWT-Custom`** | 由 Token 属性决定 | **可输出纯字符串角色名** ✅ |

**因此必须在 Casdoor 应用里改成 `JWT-Custom`**，并添加 Token 属性：

```
name = roles            category = Existing Field        value = Roles
```

这样会走 `getUserRoleNames()` 输出 `["hamkit-admin"]` 这样的**字符串数组**，`OIDC_ROLE_MAP` 才能匹配。

⚠️ 两个细节：**`tokenFields` 里列 `Roles` 不算**（那走反射，仍是对象数组）；必须用
`tokenAttributes` 的 `Existing Field` 类别。Casdoor 控制台里该入口的确切 UI 文案未在官方文档中找到
（由源码字段名推出），若界面上找不到，可用 Casdoor 配置 API 直接写 `tokenFormat` / `tokenAttributes`。

`userinfo` 端点**会**返回字符串数组角色，但**仅当 scope 含 `profile`** 时填充 —— 所以
`OIDC_SCOPE` 里的 `profile` 不要删。

### 3.6 HamKit 已为此做的兼容（本次实测后修复）

Casdoor 那种「对象数组」形态原本会让 HamKit 的角色映射**静默失效**
（`String({...})` → `"[object Object]"`，与 `OIDC_ROLE_MAP` 永远匹配不上）。

现在 `website/server/store/repositories.mjs` 的 `normalizeRoleClaim()` 已兼容三种形态：

1. 字符串数组 `["admin","editor"]`
2. 逗号/空白分隔字符串 `"admin,editor"`
3. **对象数组** `[{name:"hamkit-admin"}, …]` → 取 `name`（回退 `role`/`displayName`/`key`/`value`）

取不出名字的元素直接丢弃，**不会再产生 `[object Object]`**；未映射的 IdP 角色只会落到最低权限 `viewer`。
回归测试见 [`website/test/roles.test.mjs`](<../website/test/roles.test.mjs>)。

> 即便如此，**仍建议把 Casdoor 侧改成 `JWT-Custom`**：HamKit 的 profile 合并是
> `profile = { ...userinfo, ...idTokenClaims }`，**ID Token 覆盖 userinfo**，
> 所以只要 ID Token 里带的是对象数组，就会盖掉 userinfo 里的字符串数组。

### 3.7 兜底方案（不想动 Casdoor 的 Token 配置时）

HamKit 的角色判定优先级是：**库内人工锁定 → `BOOTSTRAP_ADMINS` → `OIDC_ROLE_MAP` → 库内既有角色 →
首个登录用户 → `viewer`**。所以即使 `OIDC_ROLE_MAP` 不可用，也可以：

```dotenv
BOOTSTRAP_ADMINS=you@example.com          # 匹配 email / username / sub，均小写比较
ALLOWED_EMAIL_DOMAINS=example.com          # 顺带收窄登录面
```

---

## 4. OIDC 端点速查（**实测**，来自真实 discovery 文档）

`GET http://127.0.0.1:8000/.well-known/openid-configuration` 的原样返回：

| 用途 | 路径 |
| --- | --- |
| Discovery | `/.well-known/openid-configuration`（应用级是 `/.well-known/<app>/openid-configuration`） |
| 授权端点 | `/login/oauth/authorize`（前端页面；用 `originFrontend`） |
| 令牌端点 | `/api/login/oauth/access_token`（POST） |
| JWKS | `/.well-known/jwks`（应用级 `/.well-known/<app>/jwks`） |
| userinfo | `/api/userinfo` |
| **登出（end_session）** | `/api/logout` |
| 刷新 / 自省 / 设备码 / 动态注册 | `/api/login/oauth/refresh_token`、`/introspect`、`/api/device-auth`、`/api/oauth/register` |

能力声明（实测原文）：

* `id_token_signing_alg_values_supported`: `RS256, RS512, ES256, ES384, ES512`
* `code_challenge_methods_supported`: `["S256"]`（**不支持 `plain`**）
* `grant_types_supported`: `authorization_code, implicit, password, client_credentials, refresh_token, device_code, token-exchange`
* `end_session_endpoint` 存在，`backchannel_logout_supported: true`

**JWKS 实测**：`[{"kty":"RSA","alg":"RS256","use":"sig","kid":"cert-built-in"}]`
—— `kid` 就是应用引用的证书名（`cert-built-in`）。

**客户端认证**：`client_secret_basic` / `client_secret_post` / JWT 客户端断言 三种都支持。
**PKCE 不强制但支持**，且规则有个细节：**没走 PKCE → 必须给正确的 `client_secret`；
走了 PKCE → `client_secret` 可留空（公共客户端），但一旦提供就必须正确**。
所以公共客户端的正确姿势是「带 PKCE + 完全不带 secret」，HamKit 侧对应
`OIDC_CLIENT_AUTH_METHOD=none` 且 **清空** `OIDC_CLIENT_SECRET`。

授权码 5 分钟有效且禁止重放。

---

## 5. 生产配置与安全清单

`conf/app.conf` 关键项：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `httpport` | `8000` | 监听端口；`web.Run(":port")` → **恒为 0.0.0.0，没有"仅绑本机"开关** |
| `runmode` | `dev` | 改 `prod`；`dev` 会**额外暴露 `/swagger`** |
| `origin` | 空 | **决定 OIDC issuer**，反代场景必设 |
| `originFrontend` | 空 | 决定 `authorization_endpoint`；前后端同域可不设 |
| `redisEndpoint` | 空 | 空则会话落本地 `./tmp`；**多副本必须配 Redis**，否则副本间会话不共享 |
| `sessionCookieLifeTime` | 空 | 空/≤0 → 默认 30 天 |
| `staticBaseUrl` | `https://cdn.casbin.org` | 初始化数据引用它；**内网部署要改**，否则内置头像/图标指向公网 |
| `inactiveTimeoutMinutes` | 空 | 空/≤0 不超时 |
| `tableNamePrefix` | 空 | 多套部署共用一个库时用 |

> `sessionOn` 这个键**在当前版本不存在也不生效**：会话被源码硬编码开启。
> 另外官方 Docker 文档里的 `-e GIN_MODE=release` 疑似文档残留（后端是 Beego，全仓库无 `GIN_MODE`）。
> **任意 `app.conf` 键都可用同名环境变量覆盖**，这是官方保证的行为。

**反向代理（Nginx）——`Host` 头是关键**（issuer 依赖它，除非已设 `origin`）：

```nginx
location / {
    proxy_pass http://casdoor:8000;
    proxy_set_header Host              $host;
    proxy_set_header X-Real-IP         $remote_addr;
    proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

上线前清单：

- [ ] **改掉 `built-in/admin` 的默认密码 `123`**
- [ ] 设 `origin = https://id.<domain>`（否则 issuer 随 Host 漂移）
- [ ] `runmode = prod`（避免暴露 `/swagger`）
- [ ] 防火墙只放行 80/443，**不要直接把 8000 暴露公网**（Casdoor 自带管理控制台）
- [ ] 数据库、Redis 不暴露公网；数据库口令换强密码
- [ ] 容器以 uid **1000** 运行，挂载目录必须对该 uid 可写（否则 `permission denied`）

---

## 6. 运维

### 备份（必须）

| 对象 | 位置 |
| --- | --- |
| **数据库** | 外置 MySQL/PG；SQLite 部署是 `casdoor.db` |
| **上传文件** | 本地存储基目录 `files`（容器内 `/files`，镜像未声明 VOLUME，**要自己挂**） |
| `conf/app.conf` | 含数据库口令、`origin`、`redisEndpoint` |
| 本地会话 | `./tmp`（`redisEndpoint` 为空时） |
| 日志（视审计要求） | `logs/casdoor.log` |

也可用内置导出：`./casdoor --export --exportPath <file>`（导出 JSON 后退出）。

### 日志与排障

日志默认写 `logs/casdoor.log`（实测容器内 `/logs/casdoor.log`），
官方推荐 `docker compose logs casdoor` 看启动日志。启动即 panic 的常见原因：

1. 找不到配置：`app.conf was not found, it should be placed at: "<cwd>/conf/app.conf"`
2. 数据库连不上 / 建库建表失败（**容器化时 `dataSourceName` 忘了写服务名**，就是这一类）
3. `logConfig` 不是合法 JSON
4. 端口被占用
5. 卷权限（uid 1000 不可写）
6. `initDataFile` 默认 `./init_data.json`，**该文件缺失或非法会启动失败**（用官方模板部署时尤其注意）

### 升级

**换镜像 tag / 换二进制后重启即可**；数据库迁移是**自动**的（每次启动执行 XORM 同步，没有独立迁移命令）。
→ **升级前务必先备份数据库**，这是唯一的回滚手段。

### 实测的可用性数据

* 首次冷启动到 OIDC 文档可用：**约 10–15 秒**（含 MySQL 初始化）
* `docker compose restart` 后恢复：**约 5 秒**
* 重启后数据（组织、角色等）**仍在**（数据在 MySQL 卷里）

---

## 7. 与 HamKit 官网的对应关系（一页速查）

```
浏览器                 HamKit 官网 (:8787)                 Casdoor (:8000)        MySQL
  │                          │                                  │                 │
  │ GET /auth/login ────────▶│                                  │                 │
  │                          │ 生成 state/nonce/PKCE，写签名 Cookie                │
  │ ◀── 302 /login/oauth/authorize?… ──────────────────────────▶│                 │
  │ 在 Casdoor 页面登录 ────────────────────────────────────────▶│  校验/建会话     │
  │ ◀── 302 hamkit.click/auth/callback?code=… &state=… ─────────│                 │
  │ GET /auth/callback ─────▶│                                  │                 │
  │                          │ POST /api/login/oauth/access_token ───────────────▶│
  │                          │ ◀── id_token + access_token ──────│                 │
  │                          │ 校验 iss/aud/exp/nonce + JWKS 签名 │                 │
  │                          │ GET /api/userinfo ───────────────▶│                 │
  │                          │ 落库用户、按角色定权限、写审计      │                 │
  │ ◀── 302 /admin/ + 会话 Cookie ─────────────────────────────│                 │
```

要点回顾：**issuer 是 Casdoor 的 host 本身**；**回调固定 `${BASE_URL}/auth/callback` 且路径必须精确登记**；
**角色要改 `JWT-Custom` 才是字符串数组**（HamKit 已兼容对象数组，但仍建议改）。

---

## 附录：本次生产部署实录（2026-10-01）

实际把 HamKit 官网 + Casdoor 部署到了 AWS 台北区域，以下是落地参数与踩过的坑。

### 落地架构

```
Internet
   │
   ├─ hamkit.click / www.hamkit.click ──► EC2 弹性 IP 43.213.170.126
   │                                          └─ nginx :443 (Let's Encrypt)
   │                                               └─ Node :8787 (官网 + 后台 + update.json)
   │
   └─ oauth.hamkit.click ──────────────► 同一台 EC2
                                              └─ nginx :443 (Let's Encrypt)
                                                   └─ Casdoor :8000 (仅绑 127.0.0.1)
                                                        └─ MySQL :3306 (仅容器网络)
```

| 组件 | 参数 |
| --- | --- |
| 区域 / 实例 | `ap-east-2`（台北）/ `t4g.small`（2 vCPU, 1.8 GiB, arm64） |
| 存储 | 25 GB gp3，加密 |
| 系统 | Ubuntu 24.04.5 LTS (aarch64) |
| 弹性 IP | `43.213.170.126`（必须用 EIP，否则实例重启后 IP 变化会导致 DNS 失效） |
| 网站 | `/srv/hamkit/website`，systemd 单元 `hamkit`，Node 22，监听 127.0.0.1:8787 |
| Casdoor | `/srv/hamkit/casdoor`，镜像 `casbin/casdoor:4.13.0` + `mysql:8.0`，只绑 127.0.0.1:8000 |
| 证书 | Let's Encrypt，`certbot.timer` 自动续期（89 天有效期） |
| 交换分区 | 2 GB（1.8 GiB 内存跑 MySQL+Casdoor 建议加） |

### 部署顺序（关键：DNS 切换放在最后）

1. 建实例 + 弹性 IP + 安全组（22 / 80 / 443，**不要长期放行 8000**）
2. 装 Node 22 / nginx / certbot，上传网站，起 systemd 服务，本机自检
3. 先加 `oauth.hamkit.click` 的 A 记录 → 签发它的证书（此时主域名还指向旧服务器）
4. 部署 Docker + Casdoor（`origin` 设为 `https://oauth.hamkit.click`）
5. **最后**才把 `hamkit.click` / `www` 指向新服务器 → 签发主域名证书
6. 配置 Casdoor 应用与角色 → 官网填 OIDC 凭据 → 端到端验证登录

### 本次踩到的坑（都已在文档与脚本中修正）

| 坑 | 现象 | 解决 |
| --- | --- | --- |
| **nginx 注释不能用 `;`** | `nginx -t` 报 `unexpected ";"` | nginx 只认 `#` 注释 |
| **`http2 on;` 需要 nginx ≥ 1.25** | Ubuntu 24.04 自带 1.24 → `unknown directive "http2"` | 去掉该指令，用 `listen 443 ssl` |
| **ACME 目录会被 `location /` 抢先** | 证书能签但**无法自动续期** | 用 `location ^~ /.well-known/acme-challenge/` |
| **自签名占位证书会破坏 certbot** | `live directory exists for hamkit.click`、`renewal config file is missing a required file reference` | 占位证书放到 `/etc/ssl/hamkit-placeholder/`（certbot 命名空间之外）；清掉 `renewal/*.conf` |
| **Docker tag 无 `v` 前缀** | `casbin/casdoor:v4.13.0` → `not found` | 用 `4.13.0`（git tag 才是 `v4.13.0`） |
| **镜像内置 `app.conf` 指向 `localhost:3306`** | 容器化后连不上库、启动失败 | `dataSourceName` 改成 compose 服务名 `casdoor-db` |
| **`built-in` 组织不能加普通用户** | `add-user` 报「向 built-in 组织添加新用户的功能已禁用」 | 新建组织，且该组织必须**先有属于自己的应用** |
| **用户必须与应用同组织** | 登录报 `The user: hamkit/admin doesn't exist` | 在 `hamkit` 组织建用户，或用同组织账号登录 |
| **`role` 表为空导致角色不生效** | 用户 `roles` 字段有值，但令牌里 `roles` 是 `[]` | `add-role` 要真正落库；必要时直接补 `role` 表并设 `users=["owner/name"]` |
| **`Role.users` 是 `[]string`** | `update-role` 报 `cannot unmarshal object into Go struct field Role.users of type string` | 传 `["hamkit/hamkit"]` 而不是对象数组 |
| **`role` 表 `groups`/`roles` 是保留字** | `INSERT ... (groups, roles, ...)` 语法错误 | SQL 里用反引号包裹 |
| **Go bcrypt 不认 `$2b$` 前缀** | python 生成的哈希校验失败 | 前缀替换为 `$2a$`（算法一致）；另：Casdoor 的 bcrypt **忽略 salt 字段**，只对原始密码做 bcrypt |
| **PowerShell here-string 会本地展开 `$(...)`** | 远程脚本被本地替换，得到荒唐结果（如 `Welcome to nginx`） | 用 `@'...'@` 单引号 here-string，或写成脚本文件再 `scp` |
| **AWS CLI `--change-batch` 不接受 BOM** | `Error parsing parameter '--change-batch'` | 写 JSON 时用 `UTF8Encoding($false)` |
| **本地 DNS 被拦截** | 域名解析成 `198.18.x.x` | 以 Route53 API + 权威 NS 查询为准；浏览器用 `--host-resolver-rules` |

### 验证结果（实测）

```
公网 HTTPS（TLS 校验通过）
  hamkit.click        200   23422 bytes（真实首页，非 nginx 默认页）
  www.hamkit.click    200   23422 bytes
  oauth.hamkit.click  200
  /.env|/package.json|/server/*|/data/*  →  404（内部文件不可访问）

Casdoor
  issuer = https://oauth.hamkit.click     （与官网 OIDC_ISSUER 逐字符一致）
  PKCE   = S256
  JWKS   = kid: cert-built-in, alg: RS256

OIDC 全链路（真实浏览器）
  https://hamkit.click/auth/login
    → 302 到 Casdoor 授权端点（带 state / nonce / code_challenge=S256）
    → 在 Casdoor 登录页输入账号
    → 302 回 https://hamkit.click/auth/callback
    → 落到 /admin/，会话为：
      {"authenticated":true,"user":{"username":"hamkit","role":"admin"},"idpRoles":["hamkit-admin"]}
  即 Casdoor 角色 hamkit-admin → 官网角色 admin 映射成功

后台写入链路（生产环境实测）
  用 OIDC 登录后调 POST /api/versions 发布版本 2.1.2（versionCode 15）
  → /update.json 立即同步，满足 Android Downloader.kt 的判定条件
    （versionCode > 0 且 downloadUrl 非空 → 不回落 GitHub Releases）

服务状态：hamkit / nginx / docker / certbot.timer 全部 active
容器：casdoor (healthy) + casdoor-db (healthy)
资源：磁盘 6.5G/24G（28%），内存 860Mi/1.8Gi 可用，Swap 2.0G
```

### 遗留事项（建议尽快处理）

1. **22 端口对 0.0.0.0/0 开放** —— 建议改成只允许管理 IP，或改用 SSM Session Manager。
2. **`built-in/admin` 是否还需要** —— 该账号属全局管理员，密码已在本次改为强随机；若不用可考虑禁用。
3. **`alist.hamkit.click` 仍指向旧服务器** `38.76.160.39`，按需迁移。
4. **APK 与 TLE 仍走 CloudFront**（`apk` / `tle` 子域），未受影响。
5. **回滚方式**：把 `hamkit.click` / `www.hamkit.click` 的 A 记录改回 `38.76.160.39` 即可。

---

## 附录 C：hamkit.click 的 CloudFront 接入

官网对外走 CloudFront，**`oauth.hamkit.click` 保持直连 EC2**（OIDC issuer 必须逐字符不变）。

### 架构

```
访客 ──► CloudFront (d3rycxnd8cbbfx.cloudfront.net, ACM 证书)
              │  别名: hamkit.click, www.hamkit.click
              │  viewer-protocol-policy: redirect-to-https
              │  Web ACL: 无, HTTP/2+3, IPv6 开
              ▼
        origin: cf-origin.hamkit.click  (A → 43.213.170.126, http-only)
              ▼
        EC2 nginx :80  (专用回源 server 块)
              ▼
        Node 127.0.0.1:8787

oauth.hamkit.click ──► EC2 nginx :443 ──► Casdoor 127.0.0.1:8000   （不经过 CloudFront）
```

### 关键参数

| 项 | 值 |
| --- | --- |
| 分发 ID | `E26DCM0XBM6D8N` |
| CloudFront 域名 | `d3rycxnd8cbbfx.cloudfront.net` |
| 别名 | `hamkit.click`、`www.hamkit.click`（A + AAAA 别名记录） |
| 源 | `cf-origin.hamkit.click` → `43.213.170.126` |
| 源协议 | **`http-only`**（见下方踩坑） |
| viewer 协议 | `redirect-to-https` |
| 证书 | ACM `us-east-1`（CloudFront 只认该区域的证书），到期 2027-04-17 |
| 默认行为 | AWS 托管 `CachingDisabled`（动态站点，含登录/后台） |
| `/assets/*` | AWS 托管 `CachingOptimized` —— 实测 `Hit from cloudfront` |
| 压缩 | 开启 |

### ⚠️ 踩坑记录 6：CloudFront 自定义源的四道坎

按顺序踩了一遍，记录下来省时间：

**① CloudFront 不允许用 IP 作源域名** → `The parameter origin name cannot be an IP address.`
必须给源配一个 DNS 名。建了专用记录 `cf-origin.hamkit.click` → EIP。

**注意不能用 `hamkit.click` 自己当源**：DNS 切到 CloudFront 后会形成
CloudFront → hamkit.click(=CloudFront) 的回环。

**② 自定义 `Host` 头不被允许** → `The parameter HeaderName : Host is not allowed.`
CloudFront 默认就把 viewer 的 Host 透传给源，所以不需要自定义头。

**③ 源证书必须匹配 AND 公开可信。**
- 用 `hamkit.click` 的 Let's Encrypt 证书（SAN 只有 `hamkit.click`/`www`）去连
  `cf-origin.hamkit.click` → 502（名称不匹配）
- 给 `cf-origin` 签自签名证书 → **仍然 502**（CloudFront 要求公开 CA）
- 想给 `cf-origin` 签 Let's Encrypt → ACME 校验失败：443 块的 `location /` 会把
  `/.well-known/acme-challenge/` 代理到 Node 应用，取不到校验文件

**④ 最终方案：源协议用 `http-only`。**
让 `cf-origin` 的 80 端口**直接提供内容**（不做 301），CloudFront 以 HTTP 回源。
公网侧仍由 CloudFront 强制 HTTPS，所以不降低安全性。

> 踩过的坑：最初 80 块写成 `return 301 https://...`，CloudFront 会把这个 **301 原样返回给访客**
> （每个请求都 301）。80 块必须直接 `proxy_pass`。

### ⚠️ 踩坑记录 7：加一层代理后真实客户端 IP 会丢

nginx 的 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for` 会**追加** `$remote_addr`：

| 场景 | XFF 链 | 应用 `clientIp()` 取 `xff[length - TRUST_PROXY]`（TRUST_PROXY=1）|
| --- | --- | --- |
| 直连源 | `真实IP` | ✅ `真实IP` |
| 经 CloudFront | `真实IP, CloudFront边缘IP` | ❌ **边缘IP** |

即「加一层 CDN」改变了 XFF 链深度，而 `TRUST_PROXY` 是写死的 1，于是登录审计里记录的是
CloudFront 边缘 IP（甚至为空）。

修法（nginx 侧归一化一次，应用无需改）：

```nginx
# 取 XFF 的倒数第二项：直连时只有 1 项则原样；经 CloudFront 时真实客户端在倒数第二
map $http_x_forwarded_for $hamkit_xff_client {
    "~^(?<a>.+),\s*[^,]+$"    $a;
    default                   $http_x_forwarded_for;
}
```

然后两个 server 块的 `X-Real-IP` / `X-Forwarded-For` 都改用 `$hamkit_xff_client`，
原始完整链留在 `X-Forwarded-For-Original` 便于排查。

实测修复后审计日志记录到真实出口 IP（`223.73.38.229`），而非边缘 IP。

> 顺带说明：`users.json` 里的 `lastLoginIp` 为 `null` **不是 bug** ——
> `auth.mjs` 里是 `ip: config.stats.storeIp ? ip : null`，而 `STATS_STORE_IP` 默认 `false`
> （隐私考虑，可用该环境变量开启）。审计日志始终记录 IP。

### 手动操作与回滚

```bash
# 查看分发
aws cloudfront get-distribution --id E26DCM0XBM6D8N \
  --query 'Distribution.{Status:Status,Aliases:DistributionConfig.Aliases.Items,Origin:DistributionConfig.Origins.Items[0].DomainName}'

# 刷新缓存（如改了静态资源）
aws cloudfront create-invalidation --distribution-id E26DCM0XBM6D8N --paths '/*'

# 回滚：把 hamkit.click / www 改回直连 EC2
aws route53 change-resource-record-sets --hosted-zone-id Z011718731TR5JYEESP02 \
  --change-batch file://C:/Users/Admin/Downloads/hamkit-dns-rollback.json
```

回滚材料：
- `C:\Users\Admin\Downloads\hamkit-cloudfront-rollback.txt`（说明）
- `C:\Users\Admin\Downloads\hamkit-dns-rollback.json`（可直接执行）

### 验收结果（实测）

```
DNS: hamkit.click / www.hamkit.click -> CloudFront 边缘 IP（13.226.69.x）
     oauth.hamkit.click             -> 43.213.170.126（源站，未受影响）

响应头: Via: ...cloudfront.net (CloudFront)   X-Amz-Cf-Pop: HKG54-P1

路径            状态    大小
/               200    25376 B   （真实首页）
/login          200     3092 B
/account        200     4755 B
/admin/         200     4769 B
/assets/site.css 200   33037 B   Hit from cloudfront
/assets/admin.js 200   93432 B   Hit from cloudfront
/api/health     200      131 B
/api/meta       200      267 B
/update.json    200    12753 B   （Android App 更新链路正常，versionCode=16）
/.env           404      633 B   （内部文件不可访问）

OIDC 端到端（真实浏览器，站点走 CloudFront 边缘、IdP 走源）：
  https://hamkit.click/auth/login
    → 302 到 Casdoor（redirect_uri=https://hamkit.click/auth/callback 正确保留）
    → 登录 → 回 /auth/callback → 落到 https://hamkit.click/admin/#/overview
    → 会话: {"authenticated":true,"role":"admin","idpRoles":["hamkit-admin"]}
    → 后台仪表盘完整渲染（今日访问 286、版本 2、在线会话 8）

oauth issuer 仍为 https://oauth.hamkit.click（与官网 .env 一致）

全部服务 active：nginx / hamkit / docker / certbot.timer / hamkit-tle-sync.timer
```


---

## 附录 B：TLE 数据同步（hamkit-tle → S3）

卫星 TLE 数据由服务器上的 systemd timer 每 30 分钟同步一次。

### 架构

```
GitHub: astrion-tech/celestrak-mirror（每 30 分钟自动刷新）
   │  8 个 *.tle：geo military oneweb resource science starlink stations weather
   │
   ├──► EC2 (43.213.170.126) /usr/local/bin/hamkit-tle-sync.sh
   │        │  另从 CelesTrak GP API 补 4 个 App 必需分组：
   │        │    GROUP=satnogs → satnogs.3le
   │        │    GROUP=amateur → amateur.3le
   │        │    GROUP=active  → active.3le
   │        │    GROUP=stations → 抽取出 iss.3le
   │        ▼
   └──► s3://hamkit-tle-tpe/tle/   （桶在 ap-east-2 台北，经 Gateway Endpoint 走内网）
            │
            └──► CloudFront: https://tle.hamkit.click/tle/…
                     │
                     └──► Android App（SatelliteDataSource.kt 的 TleSourceUrls.Cdn）
```

### 关键事实（实测）

| 项 | 值 |
| --- | --- |
| 桶 | `hamkit-tle-tpe`，区域 **ap-east-2**（台北；原为东京 `hamkit-tle`，见附录 D） |
| 对象前缀 | `tle/` |
| 同步频率 | 每 30 分钟（`OnUnitActiveSec=30min`，含 60s 随机延迟） |
| 脚本 | `/usr/local/bin/hamkit-tle-sync.sh`（755 root） |
| systemd | `hamkit-tle-sync.service` + `hamkit-tle-sync.timer` |
| 凭证 | `/etc/hamkit-tle/credentials`（**600 root:root**），IAM 用户 `hamkit-tle-sync` |
| IAM 策略 | `hamkit-tle-sync-policy-tpe`：仅 `s3:PutObject/GetObject/DeleteObject` on `hamkit-tle-tpe/tle/*` + `s3:ListBucket`（限 `tle/` 前缀） |
| 出网路径 | 经 **S3 Gateway Endpoint** `vpce-0f285607cc34a0a7b` 走内网，不经过 IGW |
| 日志 | `/var/log/hamkit-tle-sync.log`（超 5MB 自动轮转一次），另有 journald |
| 状态 | `/var/lib/hamkit-tle/last-refresh.state` 存上次同步的 `LAST_REFRESH` |

### 增量逻辑

先读仓库的 `LAST_REFRESH`；与 `/var/lib/hamkit-tle/last-refresh.state` 相同则**直接退出**，不下载。
远端数据每 30 分钟才变一次，因此绝大多数触发都是空转，几乎不消耗流量。

### 手动操作

```bash
sudo /usr/local/bin/hamkit-tle-sync.sh              # 正常同步（有变化才动）
sudo /usr/local/bin/hamkit-tle-sync.sh --force      # 忽略新鲜度判断，强制同步
sudo /usr/local/bin/hamkit-tle-sync.sh --dry-run    # 只显示会做什么
sudo /usr/local/bin/hamkit-tle-sync.sh --prune      # 额外删除 S3 上仓库已不存在的文件

systemctl list-timers hamkit-tle-sync.timer         # 看下次运行时间
journalctl -u hamkit-tle-sync.service -n 50         # 看执行日志
tail -f /var/log/hamkit-tle-sync.log                # 看脚本日志
```

### ⚠️ 踩坑记录 1：镜像仓库不提供 App 需要的文件

**这是本任务最重要的发现。** App 端 `SatelliteDataSource.kt` 写死的是：

```kotlin
satnogs = "https://tle.hamkit.click/tle/satnogs.3le"
amateur = "https://tle.hamkit.click/tle/amateur.3le"
active  = "https://tle.hamkit.click/tle/active.3le"
iss     = "https://tle.hamkit.click/tle/iss.3le"
```

但 `astrion-tech/celestrak-mirror` 仓库**只有 8 个 `.tle` 文件，没有这四个**（实测全部 404，
且 `astrion-tech` 组织下只有这一个仓库）。若只同步仓库，这 4 个文件会停留在 **2026-09-14**，
App 一直读过期星历 —— TLE 过期后定轨误差迅速增大，**且不报错**，属静默功能退化。

因此脚本额外从 **CelesTrak 官方 GP API** 取这些分组。

### ⚠️ 踩坑记录 2：CelesTrak 的「下载窗口」节流

实测现象：对同一 `GROUP` 重复请求会返回 **403**，正文是：

```
GP data has not updated since your last successful download of GROUP=active at 2026-10-01 07:23:29 UTC.
Data is updated once every 2 hours.
```

关键特征（都实测确认）：

- **与 FORMAT 无关** —— `FORMAT=tle/3le/csv/json/xml` 全部 403
- **与 User-Agent 无关** —— 换浏览器 UA 一样 403
- **按 GROUP 独立计数** —— 请求过 `active` 不影响 `starlink`
- 数据**每 2 小时**更新一次

所以脚本对每个分组做 **2 小时本地缓存**（`/var/lib/hamkit-tle/group-state/<group>.ts`），
窗口内直接跳过、不发起请求；真的收到 403 时视为「暂无新数据」，**保留 S3 现有文件**。

> 顺带确认：**CSV 格式仍受支持**。用一个没请求过的分组验证 ——
> `GROUP=starlink&FORMAT=csv` 返回 200 且首行是 `OBJECT_NAME,OBJECT_ID,EPOCH,…`。
> 所以 `active.csv` 可以继续供上。

### ⚠️ 踩坑记录 3：两条更新线必须解耦

仓库每 **30 分钟**刷新，CelesTrak 每 **2 小时**更新。脚本最初把「仓库新鲜度检查」放在最前面，
数据未变化时**整体 exit** —— 结果仓库没变的那些轮次里，App 分组永远不会被刷新，
`active.csv` 会长期停在旧版本（这正是它从 8-16 起就没再更新的原因）。

修复后两条线独立判断：

```
仓库线：LAST_REFRESH 与本地标记相同 → 只跳过仓库下载，不 exit
App 分组线：按每分组 2 小时窗口独立判断，始终会执行到
```

### `active.csv` 与 `active.3le` 的分工（已核实源码）

| 文件 | 谁在读 | 解析方式 |
| --- | --- | --- |
| `active.3le` | 默认流程 `fetchActiveTLEs()` | `parseThreeLineTLEs()` —— **3le 文本** |
| `active.csv` | **自定义 URL** `fetchCustomTLEs()` | 内容含 `OBJECT_NAME` 时走 `parseActiveCsv()` |

即：CSV 解析器不是死代码，但**默认流程不用它**；只有用户配置了返回 CSV 的自定义 URL 才会走到。
两者都产出，互不影响。同步参数已包含 `--include '*.csv'`，因此 `--prune` 也不会误删它。

### 本地 TLE → CSV 转换（绕开 CelesTrak 的 2 小时窗口）

CelesTrak 的窗口节流让 CSV 有时取不到新数据。由于 CSV 的每个字段都能从 TLE 固定列位
无损取出，脚本会在官方 CSV 拿不到时，用刚取到的 `active.3le` **本地生成** `active.csv`。

```
/usr/local/bin/tle2csv.mjs        # 转换器
/usr/local/bin/validate-csv.mjs   # 结构+可解析性自检（必填列、parseDouble 兼容）
/usr/local/bin/roundtrip-csv.mjs  # 往返校验（还原回 TLE 并核对校验和）
```

流程：`tle2csv` 生成 → `validate-csv` 自检 → 通过才替换 → 内容与现有文件相同则跳过（省一次上传）。
任一步失败都**保留 S3 上的现有文件**，不做破坏性覆盖。

### 转发器表镜像（`tle/transmitters.json`）

`db.satnogs.org/api/transmitters/?format=json` 全量十几 MB，每条记录有 30+ 个字段，
App 实际只用 7 个（`norad_cat_id` / `name` / `uplink_low` / `downlink_low` /
`uplink_mode` / `downlink_mode` / `mode` / `inverted` / `status`）。

同步脚本第 4c 段把它**裁剪**后镜像到 `tle/transmitters.json`：

```
/usr/local/bin/trim-transmitters.mjs   # 裁剪器（源文件 website/deploy/trim-transmitters.mjs）
```

裁剪规则**照抄 Android `TransmitterDataSource.parseTransmitterItem`**：

| 规则 | 说明 |
| --- | --- |
| `norad_cat_id > 0` | 否则丢弃该条 |
| `uplink_low` / `downlink_low` 至少一个 > 0 | 否则丢弃 |
| `mode` | `uplink_mode` → `downlink_mode` → `mode` |
| `name` | `name` → `description` → `<mode> <频率 MHz>`（中括号剔除、字面量 `null` 视为缺失） |
| `status` | 缺省 `unknown` |
| `inverted` | 缺省 `false`，`invert` / `inverted` 两个字段名都认 |

这样「CDN 镜像」与「直连 SatNOGS」两条路解析出来的结果逐条一致，切换源不会改变界面。

- 刷新窗口 24 小时（上游变动很慢）；原始记录数 < 3000 视为半截响应，**保留 S3 现有文件**；
- 输出仍是 JSON 数组、字段名保持 SatNOGS 原名，App 解析器无需改动；
- 未安装裁剪器时脚本退回「直接镜像原始 JSON」（体积更大但可用）；
- 客户端取数顺序：主 `tle.hamkit.click/tle/transmitters.json`，备 `db.satnogs.org`。

实测（构造用例）：6 条 → 4 条，非法 norad、双频率皆空的记录被丢弃，`norad_cat_id`
写成字符串、`inverted` 写成 `"true"` 的记录仍能正确入库。

实测（全量 15,968 颗卫星）：

```
往返验证：15968 颗卫星，239520 个字段
  字段不一致: 0
  校验和错误: 0
  历元漂移 >1ms 的条数: 0
  ✅ 全部字段无损、校验和正确
```

### ⚠️ 踩坑记录 4：TLE 指数记法必须转成显式 E 记法（最容易错的一处）

**不能把 TLE 的字段原样抄进 CSV。** App 侧用 `toDoubleOrNull()` 读 CSV，而 TLE 的隐式记法
（`76931-4`、`00000+0`）它解析不了 —— 解析失败会**静默变成 0**，卫星会带着错误的阻力系数去定轨。

而且 TLE 的指数记法有两套，差 5 个数量级，必须区分：

| 记法 | 含义 | 用在哪 |
| --- | --- | --- |
| 小数点**在最前** | `0.NNNNN × 10^±E` | **CelesTrak 的 GP 接口**（本转换器用这套） |
| 小数点在第 2 位 | `N.NNNN × 10^±E`（即 ×1e-5） | 标准 TLE 规范 |

判定依据来自 App 自己的注释：文档里写 `"37201857-3"` ↔ CSV `.37201857E-3`，
两者都等于 3.72e-4，只有「小数点在最前」这一套成立。
再用真实 CelesTrak CSV 反证：`"90072738-3"` 对应 CSV 值 `.90072738E-3`，完全吻合。

**注意 `MEAN_MOTION_DDOT` 用的是同一套规则**（不要被 `00000+0` 看起来像"显式小数"误导）。
实测旧 CelesTrak CSV 的这一列里有 `.9877866E-5`、`.90072738E-3` 等非零值，据此可反推出规则。

### ⚠️ 踩坑记录 5：三处已确认的细节

1. **`ELEMENT_SET_NO` 的列位是 `[65..68]`**（4 字符，左补空格），不是 `[65..67]`。
   写成 3 字符会把 `999` 截成 `99`。对应 App 的 `padStart(elementSetNo, 5, ' ')`。
2. **`OBJECT_ID` 的 piece 可达 3 字符**：`2013-066AA` ↔ TLE `13066AA`。
   App 的 `formatIntlDesignator` 正则用了 `([A-Z0-9]?)` 只捕获 1 个字符，
   而 CelesTrak 真实 CSV 里存在 `2013-066AA`/`2014-033AD` 这类值。
   （**App 侧这处正则建议改为 `([A-Z0-9]{1,3})`**，否则这类卫星的编号会在往返中丢字符。
   转换器侧已按 `{1,3}` 正确处理，不受影响。）
3. **`EPOCH` 只保留 6 位小数秒**（实测 16340/16340 条均为 6 位），TLE 原本是 8 位年内日。
   因此存在 ≤0.5 微秒的舍入 —— 这是 CelesTrak 自身的惯例，不是精度缺陷
   （对比：TLE 的 1e-8 天内精度约合 0.86 毫秒、约 6.7 米沿轨距离）。


### 手动操作

```bash
sudo /usr/local/bin/hamkit-tle-sync.sh              # 正常同步（有变化才动）
sudo /usr/local/bin/hamkit-tle-sync.sh --force      # 忽略仓库新鲜度判断
sudo /usr/local/bin/hamkit-tle-sync.sh --dry-run    # 只显示会做什么
sudo /usr/local/bin/hamkit-tle-sync.sh --prune      # 额外删除 S3 上已不存在的 .tle/.3le/.csv

systemctl list-timers hamkit-tle-sync.timer         # 下次运行时间
journalctl -u hamkit-tle-sync.service -n 50         # 执行日志
tail -f /var/log/hamkit-tle-sync.log                # 脚本日志
cat /var/lib/hamkit-tle/group-state/active.ts       # 某分组上次请求时间戳
```

> 注意：`--force` 只跳过**本地**窗口判断，绕不过 CelesTrak 服务端的 403。

### 依赖：服务器需要 AWS CLI

台北实例**预装清单里没有 AWS CLI**（首跑直接报 `缺少依赖: aws`）。安装方式：

```bash
# arm64
curl -fsSL -o awscliv2.zip https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip
unzip -q awscliv2.zip && sudo ./aws/install --update
aws --version   # 实测装到 2.37.7
```

### 同步结果（实测）

```
s3://hamkit-tle/tle/
  2026-10-01 07:23:24    2682624 active.3le      ← App 默认流程（已刷新）
  2026-08-16 10:40:16    2466647 active.csv      ← App 自定义 URL（待 09:27 窗口开启后自动刷新）
  2026-10-01 07:27:22      15960 amateur.3le     ← 已刷新
  2026-10-01 07:23:24      95256 geo.tle
  2026-10-01 07:27:22        336 iss.3le         ← 已刷新
  2026-10-01 07:23:24       4032 military.tle
  2026-10-01 07:23:24     109368 oneweb.tle
  2026-10-01 07:23:24      28056 resource.tle
  2026-10-01 07:27:22     105672 satnogs.3le     ← 已刷新
  2026-10-01 07:23:24       7728 science.tle
  2026-10-01 07:23:24    1794408 starlink.tle
  2026-10-01 07:23:24       3360 stations.tle
  2026-10-01 07:23:24      11928 weather.tle
```

App 端经 CloudFront 实测（HTTP 200，字节数与 S3 一致）：

```
https://tle.hamkit.click/tle/satnogs.3le     200    105672 B
https://tle.hamkit.click/tle/amateur.3le     200     15960 B
https://tle.hamkit.click/tle/active.3le      200   2682624 B
https://tle.hamkit.click/tle/active.csv      200   2466647 B   （16,341 行，含 OBJECT_NAME 表头）
https://tle.hamkit.click/tle/iss.3le         200       336 B
https://tle.hamkit.click/tle/stations.tle    200      3360 B
https://tle.hamkit.click/tle/starlink.tle    200   1794408 B
```

### 注意：`active.3le` 与 `active.csv` 内容是不同表示

- `active.3le` 2.68 MB —— 3le 文本（每星 3 行）
- `active.csv` 2.47 MB —— CSV（16,341 行，含表头）

两者都来自 `GROUP=active`，但格式不同、用途不同，**不要互相替代**。

---

## 附录 D：S3 从东京迁移到台北 + S3 Gateway Endpoint

把 `tle`、`apk` 两个桶从东京（ap-northeast-1）迁到台北（ap-east-2），
并创建 S3 Gateway Endpoint 让 EC2 访问 S3 走内网。

### 为什么必须一起做

**S3 Gateway Endpoint 只能路由「同区域」的桶**。EC2 在 ap-east-2，
若桶留在 ap-northeast-1，即使建了 Endpoint，流量仍会走 IGW 出公网。
所以「迁桶」和「建 Endpoint」是一件事的两半。

### ⚠️ 关键约束：S3 桶名全局唯一，不能同名迁移

`hamkit-tle` / `hamkit-apk` 这两个名字已被东京的桶占用，**台北无法创建同名桶**。
因此台北用带 `-tpe` 后缀的新名：

| 原（东京 ap-northeast-1） | 新（台北 ap-east-2） |
| --- | --- |
| `hamkit-tle` | `hamkit-tle-tpe` |
| `hamkit-apk` | `hamkit-apk-tpe` |

> 好消息：站点与 App 里**没有任何硬编码的桶名或 S3 地址**（已全仓搜索确认），
> 对外一律通过 `tle.hamkit.click` / `apk.hamkit.click` 访问，所以换桶名对用户无感。

### 迁移后的架构

```
EC2 (ap-east-2c)
  ├── TLE 同步脚本 ──┐
  │                  ├──► S3 Gateway Endpoint vpce-0f285607cc34a0a7b（内网）
  │                  │         └──► s3://hamkit-tle-tpe/tle/    (ap-east-2)
  └── （其他 S3 访问）┘
                                     ▲
CloudFront (tle.hamkit.click) ───────┘  经 OAC 读取

CloudFront (apk.hamkit.click) ──► s3://hamkit-apk-tpe/          (ap-east-2)
```

### 关键资源

| 项 | 值 |
| --- | --- |
| 台北 TLE 桶 | `hamkit-tle-tpe`（ap-east-2），已开版本控制 + 阻断公开访问 |
| 台北 APK 桶 | `hamkit-apk-tpe`（ap-east-2），同上 |
| Gateway Endpoint | `vpce-0f285607cc34a0a7b`，类型 Gateway，服务 `com.amazonaws.ap-east-2.s3` |
| 关联路由表 | `rtb-0c91da5388021539e`（VPC 主路由表） |
| 新增路由 | `pl-2b866342`（S3 前缀列表）→ `vpce-0f285607cc34a0a7b` |
| IAM 策略 | `hamkit-tle-sync-policy-tpe`（旧的 `hamkit-tle-sync-policy` 已解绑） |
| CloudFront 源 | `hamkit-tle-tpe.s3.ap-east-2.amazonaws.com` / `hamkit-apk-tpe.s3.ap-east-2.amazonaws.com` |

### 迁移步骤（实际执行顺序）

1. 在 ap-east-2 建两个桶，开版本控制、阻断公开访问
2. `aws s3 sync` 从东京桶复制到台北桶（跨区域）
3. 配置新桶的 OAC 桶策略（复用原有 OAC，只改桶 ARN）
4. 把 CloudFront 分发的源域名切到新桶
5. 验证 CDN 仍可访问、且**确实从台北桶读取**
6. 创建 S3 Gateway Endpoint 并关联路由表
7. 更新服务器同步脚本的 `BUCKET` / `REGION`，更新 IAM 策略

> 顺序上「先复制、后切换」是关键：东京桶在整个过程中保持可服务，随时可切回。

### ⚠️ 踩坑记录 8：`$ACCT:` 被 PowerShell 当成作用域变量

写桶策略时用了双引号 here-string：

```powershell
"Resource": "arn:aws:s3:::hamkit-tle-tpe/*",
"Condition": { "StringEquals": { "AWS:SourceArn": "arn:aws:cloudfront::$ACCT:distribution/$D_TLE" } }
```

结果 ARN 被静默写成 `arn:aws:cloudfront::/E1JYU8KGHNW0IA` ——
**缺少账号 ID 和 `distribution` 段**。原因是 PowerShell 把 `$ACCT:` 解析成
作用域变量引用（类似 `$env:`）。

→ **两个 CDN 立即全线 403**（CloudFront 拿不到桶的读权限）。

修法：用字符串拼接 `'arn:aws:cloudfront::' + $ACCT + ':distribution/...'`，
并在写入后**回读校验 ARN 是否含 12 位账号**再继续。

### ⚠️ 踩坑记录 9：跨区域复制的 ETag 会不同，但不代表内容不一致

复制 APK 后比对发现 ETag 完全对不上：

```
源  : size=56407556  etag=814ed2cc...-4   （4 个分片）
目标: size=56407556  etag=2f08ed53...-7   （7 个分片）
```

这是**分片上传的正常现象**：S3 对分片上传的对象，ETag 是分片校验值的组合，
而分片大小取决于上传工具（`s3 sync` 与服务端的默认分片大小不同），
所以 ETag 不能用来比对这类对象。

→ 正确做法是**比对内容**：分段（头/中/尾各 1MB）读取并比 SHA-256。
实测两段内容完全一致，确认迁移无损。

### 验证结果（实测）

```
桶内对象完整性（逐 key/size/etag 比对）
  hamkit-tle -> hamkit-tle-tpe : 13/13 对象 ✅
  hamkit-apk -> hamkit-apk-tpe : 内容一致（分段 SHA-256 比对通过；ETag 因分片不同不适用）

权威确认「CDN 确实读台北桶」
  台北桶写入 tle/__probe__.txt = "FROM-TAIPEI"
  东京桶写入同名     = "FROM-TOKYO"
  经 https://tle.hamkit.click/tle/__probe__.txt 读取 → "FROM-TAIPEI" ✅

Gateway Endpoint
  路由表新增: pl-2b866342 → vpce-0f285607cc34a0a7b（比 0.0.0.0/0 → igw 更具体，S3 流量必然走它）
  从 EC2 用 IAM 凭证 head-object 台北桶成功 ✅

CDN 可达性
  tle.hamkit.click/tle/active.3le   200  2681616 B
  tle.hamkit.click/tle/active.csv   200  2375380 B
  tle.hamkit.click/tle/satnogs.3le  200   105672 B
  tle.hamkit.click/tle/iss.3le      200      336 B
  apk.hamkit.click/.../release.apk  200  21829508 B
  apk.hamkit.click/.../debug.apk    200  56407556 B

同步任务
  脚本 BUCKET=hamkit-tle-tpe  REGION=ap-east-2，timer active
  完整同步成功：写往 s3://hamkit-tle-tpe/tle/，.tle=8 / .3le=4 / .csv=1
```

### 回滚方式

东京桶**仍完整保留**（`hamkit-tle` 13 个对象、`hamkit-apk` 4 个对象），回滚只需：

1. CloudFront 分发源改回 `<bucket>.s3.ap-northeast-1.amazonaws.com`
   （两个分发：`E1JYU8KGHNW0IA` 为 tle、`E3OUERALJ56FWM` 为 apk）
2. 服务器脚本 `BUCKET` / `REGION` 改回 `hamkit-tle` / `ap-northeast-1`
3. IAM 用户重新绑定旧策略 `hamkit-tle-sync-policy`

> 确认稳定运行一段时间后，可删除东京桶释放空间（tle 7 MB、apk 75 MB）。

### 注意事项

- **CloudFront 边缘仍会缓存旧内容**。源切换后，边缘上已缓存的 TLE 文件会继续提供，
  直到 TTL 过期或被 invalidate。如需立即生效：
  `aws cloudfront create-invalidation --distribution-id E1JYU8KGHNW0IA --paths '/*'`
- **跨区域迁移会产生一次性流量费**（约 82 MB 出网），之后同区域内免费。

---

## 附录 E：官网首页内容按「代码实际功能」对齐（2026-10-11）

### 背景

首页文案停留在早期版本，**漏掉了一整批已经实现的功能**：没有 APRS、没有 QSO 日志、
没有 HF Fax、没有卫星自定义分类，且 SSTV 只写了「11 种模式」（实际 20 种，且已支持
发射与图库）。本次以代码为准把首页补齐。

### 改动范围（三个文件，缺一不可）

| 文件 | 作用 | 为什么必须一起改 |
|------|------|------------------|
| `website/index.html` | 静态兜底文案 | API 不可用时首页就靠它，必须与功能一致 |
| `website/public/home.js` | 功能卡图标表 | 新增功能卡要新图标，否则退化成默认图标 |
| `website/server/store/repositories.mjs` | `DEFAULT_CONTENT` | `/api/public/content` 的真实数据源 |

> 首页内容有**三层来源**：静态 HTML → `DEFAULT_CONTENT` → `data/content.json`（后台保存）。
> 本次线上没有 `content.json`，所以 `DEFAULT_CONTENT` 直接生效。
> 若哪天在后台点过「保存」，则以后改文案必须走后台，否则不会生效。

### 功能卡：6 张 → 11 张

新增 —— `satellite-category`（卫星自定义分类）、`sstv-tx`（SSTV 发射与图库）、
`hf-fax`（HF Fax 气象传真）、`aprs`（APRS 位置报告）、`qso`（QSO 通联日志）。

同时修正了原有卡片的过时描述：

- **SSTV**：11 种 → **20 种模式**（PD / Robot / Martin / Scottie / Wrasse 全族），
  并补上信号瀑布图、盲扫、失谐补偿
- **卫星**：补上实时多普勒与 AMSAT 状态
- **CW**：明确为 Koch 课程 / 字符组 / 呼号 / 文本四类训练
- **APRS**：写清合规立场 —— 不计算不发放 Passcode，未填写时 `pass -1` 只读连接

### 图标 id 的两种写法都认

后台功能卡的 `id` 是自由文本，历史配置里可能写 `hf_fax`、也可能写 `hf-fax`。
`home.js` 的 `featureIcon()` 因此做了归一化：

```js
const key = String(id || '').trim().toLowerCase();
return FEATURE_ICONS[key] || FEATURE_ICONS[key.replace(/_/g, '-')] || FEATURE_ICONS.default;
```

### 部署方式（最小面，不动数据）

用 `casdoor-lab/deploy-site-content.py`：上传 3 个文件到 `/tmp` → 备份原文件 →
`install -o hamkit -g hamkit` 就位 → `daemon-reload` + 重启 `hamkit` → 本机自检。

> **不要**直接跑 `deploy/deploy.sh`：它会重新下发 `nginx-hamkit.conf`，
> 而线上那份带 CloudFront 真实客户端 IP 的 `map` 指令，会被覆盖掉。
> 数据目录 `data/` 也不在本次改动范围内。

### ⚠️ 踩坑记录 10：整页截图出现大片空白，是截图伪影不是页面坏了

用 `Page.captureScreenshot` + `captureBeyondViewport` 截整页时，
`section#showcase` 与 `section#stack` 在图上变成纯背景色，看起来像内容没渲染。

实测排除：CDP 读取几何数据显示两段 `height` 分别为 1170 / 815、`opacity: 1`、
11 张 `.note` 全部 `is-visible`；改用**不开 `captureBeyondViewport`、滚动后只截视口**，
内容完整可见。

**结论**：是整页合成（背景 canvas 为 `position: fixed` 全视口）导致的伪影。
排查这类问题时用「滚动 + 视口截图」，不要用整页截图下判断。

### 验收结果（实测）

```
本机测试         95 passed / 0 failed
CDP 页面验收     home：无 JS 错误，正文 3676 字符，26 个 reveal 元素全部可见
线上（CloudFront）feature 卡片 11 张
                  APRS 位置报告 / QSO 通联日志 / HF Fax 气象传真 /
                  SSTV 发射与图库 / 卫星自定义分类 / 信号瀑布图 / 20 种模式  全部命中
/assets/home.js  'hf-fax' / 'sstv-tx' / 'satellite-category' + 归一化逻辑 均在
/api/public/content  11 张功能卡，hero 文案已更新
/login /account /admin/ /version.json /update.json  均 200
```

### 回滚

```bash
# 服务器上按备份戳恢复（戳在部署输出里，形如 20261011T054708Z）
sudo cp -a /tmp/hamkit-index-<戳>.html   /srv/hamkit/website/index.html
sudo cp -a /tmp/hamkit-home-<戳>.js      /srv/hamkit/website/public/home.js
sudo cp -a /tmp/hamkit-repos-<戳>.mjs    /srv/hamkit/website/server/store/repositories.mjs
sudo systemctl restart hamkit
```



