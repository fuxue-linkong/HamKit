# Casdoor 运行与部署调研笔记（一手来源）

> 对象：**Casdoor**（[github.com/casdoor/casdoor](https://github.com/casdoor/casdoor)）。来源限定：官方文档站 [casdoor.org](https://casdoor.org)、官方仓库源码与仓库内文件、官方 Docker Hub 仓库、Casdoor 自己的 Helm chart。未使用任何二手博客/教程；未确认项明确标注。

## 0. 来源快照与方法

| 项 | 值 |
|---|---|
| 源码 commit | `c9adca039f3c24b570567e0def8d1018e9194c9f`（2026-09-30T16:42:28+08:00，`master`） |
| 方法 | `git clone --depth 1 --filter=blob:none` + sparse-checkout（`object/ controllers/ conf/ routers/ util/`），配置/文档页直接抓取原文 |

主要来源（后文简称）：源码 [`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf)、[`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go)、[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)、[`object/init.go`](https://github.com/casdoor/casdoor/blob/master/object/init.go)、[`routers/router.go`](https://github.com/casdoor/casdoor/blob/master/routers/router.go)、[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)、[`Dockerfile`](https://github.com/casdoor/casdoor/blob/master/Dockerfile)、[`docker-compose.yml`](https://github.com/casdoor/casdoor/blob/master/docker-compose.yml)、[`conf/conf.go`](https://github.com/casdoor/casdoor/blob/master/conf/conf.go)、[`.goreleaser.yaml`](https://github.com/casdoor/casdoor/blob/master/.goreleaser.yaml)；文档 [Try with Docker](https://casdoor.org/docs/basic/try-with-docker)、[Deploying with Docker](https://casdoor.org/docs/deployment/docker)、[Server installation](https://casdoor.org/docs/basic/server-installation)、[Configuration](https://casdoor.org/docs/basic/configuration)、[Standard OIDC client](https://casdoor.org/docs/how-to-connect/oidc-client)、[Try with Helm](https://casdoor.org/docs/basic/try-with-helm)；镜像 [casbin/casdoor](https://hub.docker.com/r/casbin/casdoor)、[casbin/casdoor-all-in-one](https://hub.docker.com/r/casbin/casdoor-all-in-one)；Helm [casdoor/casdoor-helm](https://github.com/casdoor/casdoor-helm)。

## A. 版本与产物

### A1. 最新稳定版与权威版本列表

**当前最新稳定版 `4.13.0`（git tag `v4.13.0`）。** Docker Hub `casbin/casdoor` 的 `latest` 与 `4.13.0` 推送时间相同（`2026-09-30T08:53:30Z` / `08:53:28Z`），即 `latest` 指向 4.13.0（[tags API](https://hub.docker.com/v2/repositories/casbin/casdoor/tags)）；GitHub Releases 最新 tag 为 `v4.13.0`（[releases/latest](https://github.com/casdoor/casdoor/releases/latest)）。版本号规律：`3.x` 为高频小版本（`3.164.0`、`3.163.3`…），`4.0.0` 起进入新主版本；Docker Hub 共 1638 个 tag。权威列表三渠道互校：**GitHub Releases**、**Docker Hub tags**、**Helm OCI registry**（[casbin/casdoor-helm-charts](https://hub.docker.com/r/casbin/casdoor-helm-charts/tags)）。
### A2. 官方产物形态与适用场景

| 产物 | 名称/来源 | 适用场景 |
|---|---|---|
| Docker 镜像（标准） | `casbin/casdoor:<tag>` | 生产，连接自有数据库。文档原文 "Connect to your own database; suitable for production"（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)） |
| Docker 镜像（all-in-one） | `casbin/casdoor-all-in-one` | 仅快速试用。文档原文 "Casdoor + embedded SQLite / Quick try-out only; **not for production**"（同上） |
| 二进制 release 包 | `casdoor_<OS>_<arch>.tar.gz` / `.zip`，含 `web/build` 与示例 `conf/app.conf` | 裸机/VM 部署 |
| Helm chart | `oci://registry-1.docker.io/casbin/casdoor-helm-charts` | Kubernetes（K8s 1.19+、Helm 3.8+） |
| 源码构建 | 同仓库前后端；后端 Go+Beego+XORM，前端 React | 二次开发 |

`v4.13.0` 实际资产：`casdoor_Linux_x86_64.tar.gz`、`casdoor_Linux_arm64.tar.gz`、`casdoor_Darwin_x86_64.tar.gz`、`casdoor_Darwin_arm64.tar.gz`、`casdoor_Windows_x86_64.zip`、`casdoor_Windows_arm64.zip`、`casdoor_4.13.0_checksums.txt`（[expanded_assets/v4.13.0](https://github.com/casdoor/casdoor/releases/expanded_assets/v4.13.0)）。命名模板出自 [`.goreleaser.yaml`](https://github.com/casdoor/casdoor/blob/master/.goreleaser.yaml)（`amd64 → x86_64`、Windows 用 `zip`），archive 一并打包 `web/build` 与 `conf/app.conf`。源码构建三件套见 [Server installation](https://casdoor.org/docs/basic/server-installation)。

### A3. 镜像架构与 tag 变体

- **架构**：`casbin/casdoor` 与 `casbin/casdoor-all-in-one` 的近期 tag **同时提供 `linux/amd64` 与 `linux/arm64`**（Registry API 每 tag 的 `images[].architecture` 为 `amd64,arm64`）：[casdoor tags](https://hub.docker.com/v2/repositories/casbin/casdoor/tags)、[all-in-one tags](https://hub.docker.com/v2/repositories/casbin/casdoor-all-in-one/tags)。
- **tag 变体**：仅 `latest` 与纯语义化版本号（`4.13.0`、`4.12.0`…，**无 `v` 前缀**，与 git tag 的 `v4.13.0` 不同）、历史 `3.x`（`3.164.0` 等）。所谓"带数据库的 all-in-one"是**独立镜像仓库** `casbin/casdoor-all-in-one`，不是同仓库的 tag 后缀。
- all-in-one 行为有源码依据：[`Dockerfile`](https://github.com/casdoor/casdoor/blob/master/Dockerfile) 的 `ALLINONE` 阶段基于 `debian:latest` 且 `CMD ["/docker-entrypoint.sh"]`；[`docker-entrypoint.sh`](https://github.com/casdoor/casdoor/blob/master/docker-entrypoint.sh) 在未设 `driverName` 时默认 `sqlite`、`dataSourceName="file:casdoor.db?cache=shared"`。
- ⚠️ **未能确认** Docker Hub 是否有官方 `-slim`/`-alpine`/`-rootless` 等 tag 后缀变体。尝试：列出两个仓库各 50/15 个 tag 名，无非版本号 tag。

## B. Docker 单机运行

### B4. 官方 `docker run` 原文

**(a) 配置走环境变量**（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)）：

```bash
docker run \
 -e driverName=mysql \
 -e dataSourceName='user:password@tcp(x.x.x.x:3306)/' \
 -p 8000:8000 \
 casbin/casdoor:latest
```

**(b) 挂载配置文件**（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)）：

```bash
docker run -p 8000:8000 -v /folder/of/app.conf:/conf casbin/casdoor:latest
```

**(c) 生产向完整写法**（[Deploying with Docker](https://casdoor.org/docs/deployment/docker)）：

```bash
docker run -d \
  --name casdoor \
  --restart unless-stopped \
  -p 8000:8000 \
  -v $(pwd)/conf:/conf \
  -v $(pwd)/logs:/logs \
  -e GIN_MODE=release \
  casbin/casdoor:latest
```

**(d) all-in-one 最简**（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)）：`docker run -p 8000:8000 casbin/casdoor-all-in-one`

- **端口**：默认容器内 **8000** → 宿主 8000（`-p 8000:8000`）；`httpport = 8000` 是 [`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf) 默认值。
- **卷**：`/conf`（配置，Dockerfile 已内置 `conf/app.conf`，挂载即覆盖，见 [`Dockerfile`](https://github.com/casdoor/casdoor/blob/master/Dockerfile)）；`/logs`（日志，`logConfig.filename = "logs/casdoor.log"`，Dockerfile 中 `USER 1000` 已 `mkdir logs && chown`）。**`/files` 未出现在任何官方命令中**：本地文件存储基目录是进程工作目录下的 `files`（[`storage/local_file_system.go`](https://github.com/casdoor/casdoor/blob/master/storage/local_file_system.go) 中 `baseFolder := "files"`，镜像 `WORKDIR /` → 容器内 `/files`），若需持久化上传文件请自行加 `-v ...:/files`。
- **持久化位置**：元数据在数据库（靠数据库卷）；all-in-one/SQLite 为容器内相对路径 `casdoor.db`（需自行挂载）；上传文件在 `files/`；本地会话在 `./tmp`（见 B8）。
- **权限警告（官方明文）**：Casdoor 以 uid/gid **1000** 运行，挂载路径必须对 uid 1000 可写，否则 `permission denied`（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)、[Deploying with Docker](https://casdoor.org/docs/deployment/docker)）。
- ⚠️ **`-e GIN_MODE=release` 疑似无效**：后端是 **Beego**，源码中无任何 `GIN_MODE` 读取。尝试全仓库 grep，除文档外无命中。请用 `runmode` 而非它。

### B5. 官方 `docker-compose.yml` 原文

**(a) 仓库根目录版**（[docker-compose.yml](https://github.com/casdoor/casdoor/blob/master/docker-compose.yml)，**从源码构建** + MySQL）：

```yaml
version: '3.1'
services:
  casdoor:
    restart: always
    build:
      context: ./
      dockerfile: Dockerfile
      target: STANDARD
    entrypoint: /bin/sh -c './server --createDatabase=true'
    ports:
      - "8000:8000"
    depends_on:
      - db
    environment:
      RUNNING_IN_DOCKER: "true"
    volumes:
      - ./conf:/conf/
  db:
    restart: always
    image: mysql:8.0.25
    platform: linux/amd64
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: 123456
    volumes:
      - /usr/local/docker/mysql:/var/lib/mysql
```

服务 `casdoor` + `db`（`mysql:8.0.25`）；**未声明自定义网络**（用 Compose 默认网络）；卷为 `./conf:/conf/` 与宿主绝对路径 `/usr/local/docker/mysql:/var/lib/mysql`；**无 `healthcheck`**；`depends_on` 无 `condition: service_healthy`，故不为就绪等待。`RUNNING_IN_DOCKER: "true"` 有源码依据：[`conf/conf.go`](https://github.com/casdoor/casdoor/blob/master/conf/conf.go) 的 `ReplaceDataSourceNameByDocker()` 在该变量为 `true` 时把 `dataSourceName` 中的 `localhost` 替换为 Linux 下 `172.17.0.1`、其他平台 `host.docker.internal`；官方文档亦解释 "When RUNNING_IN_DOCKER is set, Casdoor uses host.docker.internal so it can connect to the database without editing app.conf."（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)）。

**(b) 文档站版**（[Deploying with Docker](https://casdoor.org/docs/deployment/docker)，**预构建镜像**、无数据库服务）：

```yaml
services:
  casdoor:
    image: casbin/casdoor:latest
    container_name: casdoor
    restart: unless-stopped
    ports:
      - "8000:8000"
    environment:
      - GIN_MODE=release
    volumes:
      - ./conf:/conf
      - ./logs:/logs
    networks:
      - casdoor-network

networks:
  casdoor-network:
    driver: bridge
```

**只定义 casdoor 一个服务**，声明 bridge 网络，**无健康检查、无 depends_on**。

### B6. 支持的数据库 / 默认 / 切换

**支持**（官方列举，基于 XORM 驱动）：MySQL、MariaDB、PostgreSQL、CockroachDB、SQL Server、Oracle、SQLite 3、TiDB（[Server installation](https://casdoor.org/docs/basic/server-installation)）。**默认 MySQL**：`driverName = mysql`、`dataSourceName = root:123456@tcp(localhost:3306)/`、`dbName = casdoor`（[`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf)），文档亦写明 "Default config uses MySQL"。**例外**：`casbin/casdoor-all-in-one` 默认 SQLite（A3）。**切换文件**：`conf/app.conf`，关键键 `driverName`、`dataSourceName`、`dbName`（PG/Cockroach 还须在 `dataSourceName` 内重复 `dbname`）。官方原文示例：

```ini
# MySQL（默认）
driverName = mysql
dataSourceName = root:123456@tcp(localhost:3306)/
dbName = casdoor

# PostgreSQL
driverName = postgres
dataSourceName = user=postgres password=postgres host=localhost port=5432 sslmode=disable dbname=casdoor
dbName = casdoor

# CockroachDB（用 postgres 驱动）
driverName = postgres
dataSourceName = user=postgres password=postgres host=localhost port=5432 sslmode=disable dbname=casdoor serial_normalization=virtual_sequence
dbName = casdoor

# SQLite3
driverName = sqlite
dataSourceName = file:casdoor.db?cache=shared
dbName = casdoor
```

官方 info 提示：PG 必须先建库、`dataSourceName` 内 `dbname` 非空且与 `dbName` 重复；CockroachDB 必须加 `serial_normalization=virtual_sequence` 且须在建库前加。Helm 的 `database.driver` 可选 `mysql`/`postgres`/`cockroachdb`/`sqlite`，**默认 `sqlite`**（与裸机默认 MySQL 不同）——[values.yaml](https://github.com/casdoor/casdoor-helm/blob/master/charts/casdoor/values.yaml)。

### B7. 首次启动初始化行为与默认管理员

**会自动建库建表并写入内置数据。** `main.go` 启动序列 `InitFlag()` → `InitAdapter()` → `CreateTables()` → `InitDb()`；`CreateTables()` 无条件调用 `ormer.createTable()`（XORM Sync 语义），仅在 `createDatabase` 为真时额外 `CreateDatabase()`（[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)、[`object/ormer.go`](https://github.com/casdoor/casdoor/blob/master/object/ormer.go)）。`--createDatabase` 默认 `false`，仓库 compose 显式传 `--createDatabase=true`。配置缺失会 panic，文案 `The Casdoor config file: "app.conf" was not found, it should be placed at: "<cwd>/conf/app.conf"`。`InitDb()` 在组织不存在时依次初始化内置组织、Permission、Provider、User、Application、Cert、LDAP，以及 API/User 的 model、adapter、enforcer（[`object/init.go`](https://github.com/casdoor/casdoor/blob/master/object/init.go)）。

**默认管理员：`admin` / `123`（写作 `built-in/admin`）。** 源码 [`object/init.go`](https://github.com/casdoor/casdoor/blob/master/object/init.go) `initBuiltInUser()` 原文：

```go
user = &User{
    Owner:             "built-in",
    Name:              "admin",
    ...
    Password:          "123",
    DisplayName:       "Admin",
    ...
    Email:             "admin@example.com",
    IsAdmin:           true,
    ...
    SignupApplication: "app-built-in",
}
```

文档三处一致：`built-in/admin / 123`（[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)、[Server installation](https://casdoor.org/docs/basic/server-installation)、[Deploying with Docker](https://casdoor.org/docs/deployment/docker)）。⚠️ **首次初始化即生效的字面量 `123`，公网暴露前必须先改密码。**

**数据初始化文件**：`initDataFile = "./init_data.json"` + `initDataNewOnly = false`，配合 `init_data.json.template` 可声明式注入组织/应用/用户/角色等（[`object/init_data.go`](https://github.com/casdoor/casdoor/blob/master/object/init_data.go)）。

### B8. 默认端口、改端口、环境变量覆盖

- **默认 8000**（[`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf)）。**监听 `:8000` 即 0.0.0.0（所有网卡）**，源码 `web.Run(fmt.Sprintf(":%v", port))`，**无"仅绑 127.0.0.1"开关**（[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)）。
- **改端口**：改 `httpport` 并重启。官方 tip："To use a different port, set httpport in conf/app.conf and restart the backend."（[Server installation](https://casdoor.org/docs/basic/server-installation)）
- **环境变量覆盖（官方保证）**："**Every Casdoor option in app.conf can be overridden with an environment variable of the same name.**"、"Environment variables override values in app.conf."（[Configuration](https://casdoor.org/docs/basic/configuration)、[Try with Docker](https://casdoor.org/docs/basic/try-with-docker)）。源码依据：`conf.GetConfigString(key)` 先 `os.LookupEnv(key)`，命中即返回，**对所有键生效**；`init()` 另对 `httpport`、`appname` 预先 `web.AppConfig.Set` 以对 Beego 自身生效（[`conf/conf.go`](https://github.com/casdoor/casdoor/blob/master/conf/conf.go)）。**变量名须与 `app.conf` 键名完全一致。**
- `./casdoor --config /etc/casdoor/app.conf` 可完全替换默认配置查找路径（[Server installation](https://casdoor.org/docs/basic/server-installation)、[`object/ormer.go`](https://github.com/casdoor/casdoor/blob/master/object/ormer.go)）。

## C. 配置与关键参数

### C9. `conf/app.conf` 运行相关关键项

默认值与作用取自官方文档表格（[Configuration](https://casdoor.org/docs/basic/configuration)），并与 [`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf) 逐项核对一致。

| 键 | 默认值 | 作用 |
|---|---|---|
| `appname` | `casdoor` | 应用名；文档注明 "currently has no practical use" |
| `httpport` | `8000` | 后端监听端口 |
| `runmode` | `dev` | `dev` / `prod`；`dev` 额外挂载 `/swagger`（见 C10） |
| `copyrequestbody` | `true` | 是否复制请求体供后续使用 |
| `driverName` | `mysql` | 数据库驱动 |
| `dataSourceName` | `root:123456@tcp(localhost:3306)/` | 数据库连接串 |
| `dbName` | `casdoor` | 数据库名 |
| `tableNamePrefix` | 空 | 表名前缀（多套部署共用库） |
| `showSql` | `false` | 日志级别高于 INFO 时打印 SQL |
| `redisEndpoint` | 空 | Redis 端点（会话）；空则本地 `./tmp`；格式 `host:port,db,password` |
| `sessionCookieLifeTime` | 空 | 会话 Cookie 秒数；空或 ≤0 用默认 30 天（2592000） |
| `origin` | 空 | 后端对外 origin。**决定 OIDC issuer**（D12） |
| `originFrontend` | 空 | 前端 origin，与后端不同时用。**决定 authorization_endpoint** |
| `staticBaseUrl` | `https://cdn.casbin.org` | 初始化数据用的静态资源 CDN；**内网部署须改**，否则初始化引用公网图床 |
| `authState` | `"casdoor"` | 登录时校验的授权应用名 |
| `defaultApplication` | `app-built-in` | 未指定应用时登录后跳转的默认应用 |
| `logConfig` | `{"adapter":"file","filename":"logs/casdoor.log","maxdays":99999,"perm":"0770"}` | 日志适配器/路径/轮转/权限 |
| `logPostOnly` | `true` | 仅记录 POST |
| `inactiveTimeoutMinutes` | 空 | 无操作自动登出分钟数；空或 ≤0 不超时 |
| `forceLanguage` / `defaultLanguage` | `""` / `"en"` | 强制 UI 语言 / 默认 UI 语言 |
| `enableGzip` | `true` | 支持时启用 gzip |
| `initDataFile` / `initDataNewOnly` | `./init_data.json` / `false` | 声明式初始化数据 |
| `quota` | `{"organization":-1,...}` | 资源配额，-1 不限 |
| `ldapServerPort` / `ldapsServerPort` / `radiusServerPort` | `389` / `636` / `1812` | 内置 LDAP / LDAPS / RADIUS 端口 |

**关于 `sessionOn`：⚠️ 该键在当前版本不存在、也不生效。** 会话被源码**硬编码开启**：`main.go` 中 `web.BConfig.WebConfig.Session.SessionOn = true`（`object/ormer.go` 的 `InitConfig()` 同样硬编码）；当前 `app.conf` 无此行，官方配置文档表格亦无该键。尝试：grep 全仓库 `sessionOn`/`SessionOn`，仅命中硬编码赋值，无配置读取；仅 **Helm chart 默认 config 模板**残留 `SessionOn = true` 一行（[values.yaml](https://github.com/casdoor/casdoor-helm/blob/master/charts/casdoor/values.yaml)）。**不要依赖 `sessionOn`。**

### C10. 生产部署必须注意的项

- **`runmode = prod`**：有源码依据的差异是 `main.go` 中 `if web.BConfig.RunMode == "dev" { web.SetStaticPath("/swagger", "swagger") }` —— `dev` 会额外暴露 `/swagger`，`prod` 不会（[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)）。
- **会话/Redis**：`redisEndpoint` 为空时会话存本地 `./tmp`（Beego `file` provider）。**多副本部署必须配 Redis**，否则副本间会话不共享；仓库 `k8s.yaml` 注释也写明 "if you don't use redis, casdoor should not have multiple replicas"（[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)、[`conf/redis.go`](https://github.com/casdoor/casdoor/blob/master/conf/redis.go)、[k8s.yaml](https://github.com/casdoor/casdoor/blob/master/k8s.yaml)）。
- **`origin` / `originFrontend`**：**直接决定 OIDC issuer 与授权端点域名**，反代场景必须显式设置，否则 issuer 退化为请求 `Host`（D12）。
- **`staticBaseUrl`**：内网/离线部署改掉默认 CDN，否则初始化写入的 favicon/avatar/logo 指向公网。
- **跨域**：`routers/cors_filter.go` 实现 CORS 过滤器，对 `/api/userinfo`、`/api/login/oauth/access_token`、`/api/acs` 有专门分支，并在 `routers/router.go` 注册为 `routers.CorsFilter`。
- **反向代理（官方 Nginx 原文）**：必须转发 `Host`、`X-Real-IP`、`X-Forwarded-For`、`X-Forwarded-Proto`：

```nginx
server {
    listen 443 ssl;
    server_name your-domain.com;

    ssl_certificate     /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    location / {
        proxy_pass http://casdoor:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

  **`proxy_set_header Host $host;` 是关键**：issuer 会从请求 Host 推导，除非设置了 `origin`（[Deploying with Docker](https://casdoor.org/docs/deployment/docker)、[`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go)）。文档同时给出 Traefik（labels / dynamic 两种）与 Caddy 等价配置，并建议 TLS + 只开放 80/443。Docker 部署别忘 `-v $(pwd)/logs:/logs`，否则容器重建丢日志。

### C11. 时区与语言

- **语言**：后端 `forceLanguage`（强制，覆盖浏览器语言）与 `defaultLanguage`（`en`）；前端对应 `web/src/Conf.js` 的 `ForceLanguage`/`DefaultLanguage`，改后需在 `web` 目录 `yarn build` 重建（[Configuration](https://casdoor.org/docs/basic/configuration)）。内置组织初始化写入的语言列表为 `["en","es","fr","de","ja","zh","vi","pt","tr","pl","uk"]`（[`object/init.go`](https://github.com/casdoor/casdoor/blob/master/object/init.go)）。
- **时区**：官方 `Dockerfile` 的 `STANDARD` 阶段执行 `apk add tzdata`，镜像内有时区库；但 ⚠️ **未能确认**存在可设应用时区的 `app.conf` 键。尝试：grep `conf/`、`util/` 的 `TZ`/`time.LoadLocation`/`Local`，仅见 `util/cert.go`、`util/time.go` 使用 `time.Local()`（跟随进程环境）。给容器设 `TZ` 环境变量（Go 运行时识别）是**推断，非官方文档结论，请实测**。

## D. OIDC 接入（把 Casdoor 当 IdP）

### D12. Application 的 OIDC issuer 到底是什么形式？（关键项）

**结论：issuer 就是 Casdoor 的「后端 origin」本身，形如 `https://<host>`，即 `scheme://host[:port]`，不含 `/<appname>`，也不含 `/.well-known/...`。**

**官方文档原文明确定论**（[Standard OIDC client](https://casdoor.org/docs/how-to-connect/oidc-client)）：

> "The `issuer` field is the same as the global issuer (`https://door.casdoor.com`), even on the application-specific discovery endpoint. This is intentional: the `iss` claim in the JWT tokens that Casdoor issues is always the backend host, so the discovery issuer must match it for standard OIDC clients to validate tokens correctly. **Earlier versions returned an application-specific issuer (`https://door.casdoor.com/.well-known/app-example`) here**, which did not match the token's `iss` claim and caused token validation to fail in strict clients."

文档示例响应中 `"issuer": "https://door.casdoor.com"`，只有 `jwks_uri` 是应用级（`https://door.casdoor.com/.well-known/app-example/jwks`）。

**源码依据**（[`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go) `GetOidcDiscovery()`）：

```go
if applicationName != "" {
    // Application-specific endpoints (owner is always "admin")
    // Issuer must match the `iss` claim in JWT tokens (always originBackend) so that
    // clients using /.well-known/<app>/openid-configuration can validate tokens correctly.
    issuer = originBackend
    jwksUri = fmt.Sprintf("%s/.well-known/%s/jwks", originBackend, applicationName)
} else {
    // Default global issuer and endpoints
    issuer = originBackend
    jwksUri = fmt.Sprintf("%s/.well-known/jwks", originBackend)
}
```

两个分支的 `issuer` **都是 `originBackend`**，与应用名无关。`iss` claim 同源：`generateJwtToken()` 中 `RegisteredClaims{ Issuer: originBackend, Subject: user.Id, Audience: []string{application.ClientId}, ... }` —— **注意 `sub` 是 `user.Id`（Casdoor UUID），不是用户名**（[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)）。`userinfo` 的 `iss` 同样取 `originBackend`（[`object/user.go`](https://github.com/casdoor/casdoor/blob/master/object/user.go) `GetUserInfo()`）。

**`originBackend` 推导规则**（同文件 `getOriginFromHostInternal()`/`getOriginFromHost()`），按优先级：

1. `app.conf` 的 `origin` 非空 → **`origin` 的值即为 issuer**（`originFrontend` 若也非空则只覆盖前端 origin）。**生产推荐显式设置该项。**
2. 否则按请求 `Host` 头 + 协议推导：Host 不含 `.`（如 `localhost:8000`）或 Host 是 IP → `http://<host>`；否则 → `https://<host>`。
3. 特例：`runmode == "dev"` 且 `Host == "localhost:8000"` → 前端 `http://localhost:7001`、后端 `http://localhost:8000`。
4. `originFrontend` 非空只覆盖前端 origin（影响 `authorization_endpoint`），**不影响 issuer**。

> **实践含义**：`origin`（或请求 Host）必须等于浏览器与 RP 都能访问的**同一个** HTTPS 地址。若走反代而 `origin` 留空，issuer 会随 `Host` 头变化；主机名一变（IP → 域名），issuer 变化会导致旧 token 校验失败、RP 侧 issuer 不匹配。

### D13. Discovery 与各端点路径

**Discovery 挂在 `/`；应用级挂在 `/.well-known/<appname>/`，不是 `/<appname>/.well-known/`。**

| 用途 | 路径（相对 origin） | 来源 |
|---|---|---|
| OIDC Discovery（全局 / 按应用） | `/.well-known/openid-configuration`、`/.well-known/:application/openid-configuration` | [`routers/router.go`](https://github.com/casdoor/casdoor/blob/master/routers/router.go) |
| OAuth2 AS Metadata（RFC 8414） | `/.well-known/oauth-authorization-server`（含 `:application` 变体） | 同上 |
| JWKS | `/.well-known/jwks`、`/.well-known/:application/jwks` | 同上 |
| WebFinger | `/.well-known/webfinger`、`/.well-known/:application/webfinger` | 同上 |
| 受保护资源元数据 | `/.well-known/oauth-protected-resource`（含 `:application` 变体） | 同上 |
| **授权端点** | `https://<originFrontend>/login/oauth/authorize` | [`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go) |
| **令牌端点** | `https://<originBackend>/api/login/oauth/access_token`（POST） | 同上 / [`routers/router.go`](https://github.com/casdoor/casdoor/blob/master/routers/router.go) |
| 刷新令牌 / 自省 | `/api/login/oauth/refresh_token`、`/api/login/oauth/introspect` | 同上 |
| **userinfo** | `/api/userinfo`（GET） | 同上 |
| 设备授权 / 动态注册 | `/api/device-auth`、`/api/oauth/register` | 同上 |
| **end_session（登出）** | `/api/logout`（GET,POST） | 同上 |
| SAML ACS / 元数据 | `/api/acs`、`/api/saml/metadata` | 同上 |

源码原文（[`routers/router.go`](https://github.com/casdoor/casdoor/blob/master/routers/router.go)）：

```go
web.Router("/api/logout", &controllers.ApiController{}, "GET,POST:Logout")
web.Router("/api/userinfo", &controllers.ApiController{}, "GET:GetUserinfo")
web.Router("/api/login/oauth/access_token", &controllers.ApiController{}, "POST:GetOAuthToken")
web.Router("/api/login/oauth/refresh_token", &controllers.ApiController{}, "POST:RefreshToken")
web.Router("/api/login/oauth/introspect", &controllers.ApiController{}, "POST:IntrospectToken")
web.Router("/.well-known/openid-configuration", &controllers.RootController{}, "GET:GetOidcDiscovery")
web.Router("/.well-known/:application/openid-configuration", &controllers.RootController{}, "GET:GetOidcDiscoveryByApplication")
web.Router("/.well-known/oauth-authorization-server", &controllers.RootController{}, "GET:GetOAuthServerMetadata")
web.Router("/.well-known/:application/oauth-authorization-server", &controllers.RootController{}, "GET:GetOAuthServerMetadataByApplication")
web.Router("/.well-known/jwks", &controllers.RootController{}, "*:GetJwks")
web.Router("/.well-known/:application/jwks", &controllers.RootController{}, "*:GetJwksByApplication")
```

**`authorization_endpoint` 用 `originFrontend`，而 `issuer`/`token_endpoint`/`userinfo` 用 `originBackend`** —— 单域名单进程部署时二者相同，只有前后端分域（或 dev 7001/8000）时才不同。

**能力声明（源码实际返回）**：`response_types_supported` 含 `code`；`grant_types_supported` 含 `authorization_code`、`refresh_token`、`client_credentials`、`password`、`implicit`、device_code、token-exchange；`code_challenge_methods_supported: ["S256"]`；`subject_types_supported: ["public"]`；`id_token_signing_alg_values_supported: ["RS256","RS512","ES256","ES384","ES512"]`；`scopes_supported: ["openid","email","profile","address","phone","offline_access"]`（应用级并入该应用自定义 scope）；`backchannel_logout_supported: true`。

### D14. 回调地址（Redirect URI）填写位置与格式

- **位置**：Casdoor 控制台 → **应用（Application）** → **Redirect URLs**（字段 `Application.RedirectUris`，JSON `redirectUris`）([`object/application.go`](https://github.com/casdoor/casdoor/blob/master/object/application.go)、[Application configuration](https://casdoor.org/docs/application/config))。
- **官方明确区分**（caution 原文）："The callback URL configured in the provider (e.g. GitHub, Google) must be Casdoor's callback URL: `http://your-casdoor-url.com/callback`. The Redirect URL configured in Casdoor must be your application's callback URL: `http://your-site-url.com/callback`."（[Application configuration](https://casdoor.org/docs/application/config)）
- **格式要求（源码 `IsRedirectUriValid()` / `redirectUriMatchesPattern()` 的实际行为）**：精确相等 → 通过；目标项可省略 scheme（按 `https://` 补全），但实际 URI 的 scheme 仍须为 `http`/`https`；**主机支持子域后缀匹配**（`redirectHost == targetHost || strings.HasSuffix(redirectHost, "."+targetHost)`，目标以 `.` 开头则纯后缀匹配）；**路径必须完全相等，不支持通配**；端口必须相同（loopback 例外）；含脚本前缀的 URL 一律拒绝；兜底为整项编译成 `^(?:<pattern>)$` 正则匹配原始字符串。来源：[`object/application_util.go`](https://github.com/casdoor/casdoor/blob/master/object/application_util.go)。
- 同一份 `redirectUris` **也用于校验 `post_logout_redirect_uri`**（见 D17）。

### D15. ID Token 里角色/权限放在哪个 claim？（关键项）

**结论按 TokenFormat 分三种，默认（`JWT`）的答案会坑到通用 RP：**

| TokenFormat | ID Token 的 `roles` | 形态 |
|---|---|---|
| **`JWT`（默认）** | **存在** | **`[]*Role` 全对象数组**（owner/name/createdTime/displayName/description…），**不是字符串数组** |
| `JWT-Empty` | **不存在** | 仅 owner/name/id/displayName/avatar/email/email_verified/phone 等极少字段 |
| `JWT-Standard` | **不存在** | 标准 OIDC claim：`sub`/`iss`/`aud`/`preferred_username`/`name`/`email`/`email_verified`/`picture`/`phone_number`/`gender`/`address` |
| `JWT-Custom` | **由 `tokenFields`/`tokenAttributes` 决定** | 可产出**字符串数组**角色名 |

**源码依据**：默认 TokenFormat 来自内置组织 `DefaultTokenFormat: "JWT"`、内置应用 `TokenFormat: "JWT"`，且 `generateJwtToken()` 中 `if application.TokenFormat == "" { application.TokenFormat = "JWT" }`（[`object/init.go`](https://github.com/casdoor/casdoor/blob/master/object/init.go)、[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)）。`JWT` 分支用 `getClaimsWithoutThirdIdp()` → `getUserWithoutThirdIdp()`，其中显式赋值 `Roles: user.Roles, Permissions: user.Permissions, Groups: user.Groups`，而 `Claims.User.Roles` 的 Go 类型是 `[]*Role`（`json:"roles"`）、`Permissions` 是 `[]*Permission` —— **所以默认 ID Token 的 `roles` 是对象数组**。`JWT-Empty` 用 `getShortClaims()` → `getShortUser()`，`UserShort` 只含 owner/name/id/displayName/avatar/email/email_verified/phone，**无 `roles`**。`JWT-Standard` 用 `getStandardClaims()` → `getStandardUser()`，**无 `roles`**（`address` 会按 `Address` 数组拼成结构化 OIDC address 对象）([`object/token_standard_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_standard_jwt.go))。

**`userinfo` 会返回字符串数组角色，但需要 `profile` scope。** `object.Userinfo` 字段为 `Roles []string \`json:"roles,omitempty"\``、`Permissions []string`、`Groups []string`，且 `GetUserInfo()` 中这些字段**只在 `strings.Contains(scope, "profile")` 分支内填充**，`Roles` 取每个 `role.Name`（[`object/user.go`](https://github.com/casdoor/casdoor/blob/master/object/user.go)）。

**`JWT-Custom` 是拿到"纯字符串角色名"的正解**，两种写法差异关键：

- `tokenFields` 列出 `Roles`/`Permissions`：走反射 `userValue.FieldByName(field)`，`res["Roles"] = userField.Interface()` —— **仍是 `[]*Role` 对象数组**。字段名归一化为 `util.SnakeToCamel(util.CamelToSnakeCase(field))`，故输出键名仍为 `Roles`（首字母大写）。
- `tokenAttributes` 用 **`category: "Existing Field"` + `value: "Roles"`**：走 `getUserFieldValue()` 的特殊分支 `case "Roles": return getUserRoleNames(user), true`，而 `getUserRoleNames()` 返回 `[]string`（每个 `role.Name`）；`Permissions` 同理，另有 `permissionNames`。**这才是输出字符串数组角色名的路径。**
- `JWT-Custom` 无论哪种都**固定包含** `iss`/`sub`/`aud`/`exp`/`nbf`/`iat`/`jti`/`tokenType`/`azp`/`nonce`/`scope`。`JwtItem` 结构为 `{Name, Category, Value, Type}`。来源：[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)、[`object/user_util.go`](https://github.com/casdoor/casdoor/blob/master/object/user_util.go)、[`object/application.go`](https://github.com/casdoor/casdoor/blob/master/object/application.go)。
- ⚠️ **未能确认** Casdoor 控制台中 Token 格式/Token 属性的**确切 UI 文案与入口**。尝试：抓取 [Application configuration](https://casdoor.org/docs/application/config)，该页不含 TokenFormat UI 说明。上述结论由源码结构体与控制台所依赖的 API 字段名（`tokenFormat`/`tokenFields`/`tokenAttributes`）推出，字段语义可靠，UI 导航路径需实测。

**另两个可能被误用的 claim**：默认 `JWT` 下还有 **`isAdmin`（bool）**（来自 `User.IsAdmin`，可在 `JWT-Custom` 的 `tokenFields` 中选取）与 **`tag`（字符串）** —— 源码注释直言 "FIXME: A workaround for custom claim by reusing `tag` in user info"，即把用户 Tag 当自定义 claim 的临时手段（[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)）。

### D16. `client_id`/`client_secret` 在哪看、支持哪些认证方式、是否强制 PKCE

- **查看位置**：Casdoor 控制台 → 应用（Application）→ 该应用的 **Client ID** 与 **Client Secret**（`Application.ClientId`/`ClientSecret`，JSON `clientId`/`clientSecret`；读取接口对 secret 做掩码 `"***"`）([`object/application.go`](https://github.com/casdoor/casdoor/blob/master/object/application.go)、[`controllers/account.go`](https://github.com/casdoor/casdoor/blob/master/controllers/account.go))。
- **支持的客户端认证方式**（[`controllers/token.go`](https://github.com/casdoor/casdoor/blob/master/controllers/token.go) `GetOAuthToken()` + `ValidateOAuth()`，三种）：① **`client_secret_basic`** —— HTTP Basic（`c.Ctx.Request.BasicAuth()`；`GetOAuthToken()` 开头在 `client_id`/`client_secret` 均空时尝试）；② **`client_secret_post`** —— 请求参数或 JSON body 的 `client_id`/`client_secret`（`ValidateOAuth()` 会 fallback 到 body 的 `TokenRequest`）；③ **JWT 客户端断言** —— `client_assertion_type == "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"` + `client_assertion`，由 `object.ValidateClientAssertion()` 校验。不支持的组合返回 `invalid_request`/`invalid_client`。
- **是否强制 PKCE：不强制，但受支持（仅 `S256`，无 `plain`）。** 授权请求可带 `code_challenge` + `code_challenge_method`（[`controllers/account.go`](https://github.com/casdoor/casdoor/blob/master/controllers/account.go)、[`controllers/auth.go`](https://github.com/casdoor/casdoor/blob/master/controllers/auth.go)）。换令牌判定逻辑（[`object/token_oauth.go`](https://github.com/casdoor/casdoor/blob/master/object/token_oauth.go) 原文）：

```go
if token.CodeChallenge != "" {
    challengeAnswer := pkceChallenge(verifier)
    if challengeAnswer != token.CodeChallenge { /* invalid_grant */ }
}

if application.ClientSecret != clientSecret {
    // when using PKCE, the Client Secret can be empty,
    // but if it is provided, it must be accurate.
    if token.CodeChallenge == "" { /* invalid_client: token.CodeChallenge: empty */ }
    else { if clientSecret != "" { /* invalid_client */ } }
}
```

  语义：**未走 PKCE** → 必须提供正确的 client_secret；**走了 PKCE** → client_secret 可留空（公共客户端），但**一旦提供就必须正确**。故公共客户端的正确姿势是「带 PKCE + 完全不带 client_secret」。`code_challenge_methods_supported` 只声明 `S256`（[`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go)、[Standard OIDC client](https://casdoor.org/docs/how-to-connect/oidc-client)）。授权码 5 分钟有效（错误文案 "code must be used within 5 minutes"），且禁止重放（`token.CodeIsUsed`）。
- 相关：应用可配 `ExpireInHours`（内置应用默认 `168`，7 天）、`RefreshExpireInHours`、`CookieExpireInHours`（默认 `720`）；`offline_access` 在 `scopes_supported` 中，用于拿 refresh token。

### D17. RP-Initiated Logout 支持与路径

**支持。`end_session_endpoint` = `https://<originBackend>/api/logout`，接受 GET 与 POST。** Discovery 中声明 `EndSessionEndpoint: fmt.Sprintf("%s/api/logout", originBackend)`，且 `backchannel_logout_supported: true`、`backchannel_logout_session_supported: true`（[`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go)）；路由 `web.Router("/api/logout", &controllers.ApiController{}, "GET,POST:Logout")`（[`routers/router.go`](https://github.com/casdoor/casdoor/blob/master/routers/router.go)）。

实现遵循 OIDC RP-Initiated Logout 1.0（源码注释给出规范链接），支持 **`id_token_hint`、`post_logout_redirect_uri`、`client_id`、`state`**（[`controllers/account.go`](https://github.com/casdoor/casdoor/blob/master/controllers/account.go)）。兼容性细节：`id_token_hint` 只是 RECOMMENDED，**缺失不报错**，退化为按当前会话登出；某些客户端（注释点名 Gitea）只发 `post_logout_redirect_uri`，故可用 `client_id` 代替 `id_token_hint` 解析应用并校验回跳；`post_logout_redirect_uri` 是**可选**的（注释引用 issue #2151）。`redirectToPostLogout()` 用 **`application.IsRedirectUriValid(redirectUri)`** 校验 —— 即**登出回跳地址也必须登记在应用 `redirectUris` 中**，否则报 `Redirect URI: %s doesn't exist in the allowed Redirect URI list`；通过后 302 并在有 `state` 时追加 `?state=`/`&state=`。另会触发 Back-Channel Logout 与 SAML SLO，并有 `/api/sso-logout`（登出全部或仅当前会话）。

## E. 运维

### E18. 数据备份要备份什么

**必须备份：数据库、`files/`、`conf/`；用本地会话时加 `./tmp`；SQLite 部署加 `casdoor.db`。**

| 对象 | 位置 | 依据 |
|---|---|---|
| **数据库** | MySQL/PG… 外置库；SQLite 为进程工作目录下 `casdoor.db` | [`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf)、[`docker-entrypoint.sh`](https://github.com/casdoor/casdoor/blob/master/docker-entrypoint.sh) |
| **上传文件** | 本地文件存储基目录 `files`（容器 `WORKDIR /` → `/files`） | [`storage/local_file_system.go`](https://github.com/casdoor/casdoor/blob/master/storage/local_file_system.go)：`baseFolder := "files"` |
| **配置** | `conf/app.conf`（含数据库口令、`origin`、`redisEndpoint`），可选 `conf/init_data.json` | [`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf) |
| **本地会话** | `./tmp`（`redisEndpoint` 为空时 Beego `file` session provider 目录） | [`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go) |
| **日志**（视审计需要） | `logs/casdoor.log` | [`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf) |

若改用对象存储（`defaultStorageProvider` + [`storage/`](https://github.com/casdoor/casdoor/tree/master/storage) 下 aliyun_oss / aws_s3 / minio_s3 / azure / google_cloud / qiniu / tencent_cos / synology_nas / local_file_system 等 provider），`files/` 不必备份，改备份对应对象存储。官方另提供导出：`./casdoor --export --exportPath <file>` 把库导出为 JSON 后退出（[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)、[`object/ormer.go`](https://github.com/casdoor/casdoor/blob/master/object/ormer.go)）。与恢复配对的是声明式初始化 `initDataFile` + `initDataNewOnly`（[`object/init_data.go`](https://github.com/casdoor/casdoor/blob/master/object/init_data.go)）。

### E19. 日志位置、查看启动日志、常见启动失败原因

**日志位置**：`logConfig` 默认 `{"adapter":"file","filename":"logs/casdoor.log","maxdays":99999,"perm":"0770"}`，即工作目录下 `logs/casdoor.log`（容器内 `/logs/casdoor.log`，Dockerfile 中 `USER 1000` 已 `mkdir logs && chown`）；`adapter` 设为 `console` 时会 `logs.Reset()` 改为输出控制台（[`conf/app.conf`](https://github.com/casdoor/casdoor/blob/master/conf/app.conf)、[`Dockerfile`](https://github.com/casdoor/casdoor/blob/master/Dockerfile)、[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)）。**查看启动日志**（官方命令原文）：`docker-compose logs casdoor` 或 `docker logs casdoor`（[Deploying with Docker](https://casdoor.org/docs/deployment/docker)）。

**常见启动失败原因（由源码 panic 点反推）**：① 找不到配置文件 → panic `The Casdoor config file: "app.conf" was not found, it should be placed at: "<cwd>/conf/app.conf"`；② 配置无法加载 → `InitFlag()` 内 `web.LoadAppConfig("ini", configPath)` 失败 panic `failed to load config from %s: %v`；③ 数据库连不上/建库建表失败 → `NewAdapter`、`CreateDatabase`、`createTable` 任一失败 panic；④ `logConfig` 非法 JSON → `json.Unmarshal` panic，或 `logs.SetLogger` 失败 panic；⑤ 端口被占用/已有实例 → `util.StopOldInstance(port)` 报错 panic（启动前会尝试终止占用该端口的旧实例）；⑥ 卷权限 → uid/gid 1000 不可写则 `permission denied`；⑦ CockroachDB 未加 `serial_normalization=virtual_sequence` → 官方警告每次启动/重启都报"数据库已存在"；⑧ 初始化文件缺失/非法 → `InitFromFile()` 中 `readInitDataFromFile` 失败 panic（**注意 `initDataFile` 默认 `./init_data.json`，该文件不存在会启动即失败**；官方 Docker 部署文档要求把 `init_data.json.template` 下载为 `conf/init_data.json`）。来源：[`object/ormer.go`](https://github.com/casdoor/casdoor/blob/master/object/ormer.go)、[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)、[`object/init_data.go`](https://github.com/casdoor/casdoor/blob/master/object/init_data.go)、[`util/process.go`](https://github.com/casdoor/casdoor/blob/master/util/process.go)、[Deploying with Docker](https://casdoor.org/docs/deployment/docker)。

### E20. 升级流程与数据库迁移

- **升级 = 换镜像 tag / 换二进制后重启。** Helm 场景：`helm upgrade casdoor oci://registry-1.docker.io/casbin/casdoor-helm-charts --version <version>`；Docker 场景即把 `casbin/casdoor:latest` 换成目标版本 tag 后重建容器（[Try with Helm](https://casdoor.org/docs/basic/try-with-helm)）。
- **数据库迁移是自动的**：每次启动都执行 `CreateTables()` → `ormer.createTable()`，基于 XORM Sync 语义自动补齐/调整表结构；**没有独立 migration 命令或版本化迁移脚本**。**升级前务必先备份数据库。**（[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)、[`object/ormer.go`](https://github.com/casdoor/casdoor/blob/master/object/ormer.go)）
- 谨慎项：`createDatabase` 默认 `false`（仅显式 `--createDatabase=true` 才执行 `CREATE DATABASE`），但 `createTable()` **始终执行**。
- ⚠️ **未能确认**存在官方跨大版本（如 3.x → 4.x）升级/迁移指南。尝试：探测 `casdoor.org/docs/deployment/upgrade` 等路径均 404，仓库根目录无 `UPGRADE.md`/`MIGRATION.md`。**结论：只能依据"自动建表 + 先备份"这一源码事实操作。**

### E21. 默认监听地址与公网暴露建议

默认监听 **`:8000`，即所有网卡（0.0.0.0 + IPv6 任意），无内置"仅本机"开关**（`web.Run(fmt.Sprintf(":%v", port))`，[`main.go`](https://github.com/casdoor/casdoor/blob/master/main.go)）。Casdoor **自带完整管理控制台**（内置 org `built-in`、内置应用 `app-built-in`、内置 admin 用户，初始化密码 `123`）([`object/init.go`](https://github.com/casdoor/casdoor/blob/master/object/init.go))。官方立场：反向代理 + TLS 是生产推荐做法（"For production deployments, it's recommended to use a reverse proxy with TLS certificates"），**不要把 8000 直接暴露公网**（[Deploying with Docker](https://casdoor.org/docs/deployment/docker)）。具体动作：① **第一时间改掉 `built-in/admin` 的默认密码 `123`**；② 防火墙/安全组只放行 80/443；③ `runmode = prod` 避免暴露 `/swagger`；④ 显式设置 `origin`，避免 issuer 随 `Host` 漂移；⑤ 容器以 uid 1000 非 root 运行（Dockerfile `USER 1000`）；⑥ `redisEndpoint` 与数据库不要暴露公网；⑦ 安全披露走官方 [`SECURITY.md`](https://github.com/casdoor/casdoor/blob/master/SECURITY.md)。

## F. 对 HamKit 官网的接入要点

把 HamKit 官网（`website/`）映射到自托管 Casdoor。HamKit 侧行为依据本仓库源码：[`.env.example`](<../website/.env.example>)、[`server/core/oidc.mjs`](<../website/server/core/oidc.mjs>)、[`server/store/repositories.mjs`](<../website/server/store/repositories.mjs>)、[`server/config.mjs`](<../website/server/config.mjs>)、[`server/routes/api.mjs`](<../website/server/routes/api.mjs>)。假设 Casdoor 对外地址 `https://id.hamkit.click`，HamKit `BASE_URL=https://hamkit.click`。

### F1. 逐项填什么

| HamKit 配置项 | 应填值 | 说明与依据 |
|---|---|---|
| `BASE_URL` | `https://hamkit.click` | 已有默认；用于拼回调地址与判断 Cookie Secure |
| **`OIDC_ISSUER`** | **`https://id.hamkit.click`** | **不带 `/app-xxx`、不带尾斜杠、不带 `/.well-known/...`**，必须与 Casdoor 的 issuer **逐字符相等**。Casdoor 文档示例 `"issuer": "https://door.casdoor.com"`，源码 `issuer = originBackend`。**强推在 Casdoor 侧显式设 `origin = https://id.hamkit.click`**，使 issuer 与请求 Host 解耦（[Standard OIDC client](https://casdoor.org/docs/how-to-connect/oidc-client)、[`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go)） |
| `OIDC_CLIENT_ID` | Casdoor 控制台 → 应用 → **Client ID** | [`object/application.go`](https://github.com/casdoor/casdoor/blob/master/object/application.go) |
| `OIDC_CLIENT_SECRET` | 同页 **Client Secret**；机密客户端填真实值 | 同上 |
| **`OIDC_CLIENT_AUTH_METHOD`** | **`client_secret_basic`**（默认，推荐）；若 Casdoor 侧为公共客户端则 `none` **且完全清空** `OIDC_CLIENT_SECRET` | Casdoor 三种方式均支持；HamKit 的 `none` 对应"仅 PKCE"，而 Casdoor 规则是"有 `code_challenge` 时 client_secret 可为空；一旦提供必须正确"，故公共客户端**必须靠 PKCE**（Casdoor 仅支持 `S256`）（[`controllers/token.go`](https://github.com/casdoor/casdoor/blob/master/controllers/token.go)、[`object/token_oauth.go`](https://github.com/casdoor/casdoor/blob/master/object/token_oauth.go)） |
| `OIDC_SCOPE` | `openid profile email`（保持默认） | **`profile` 必须保留**：Casdoor 的 `userinfo` 只在 scope 含 `profile` 时才返回 `roles`/`permissions`/`groups`（[`object/user.go`](https://github.com/casdoor/casdoor/blob/master/object/user.go)） |
| `OIDC_USERINFO_ENDPOINT` 等 | **留空** | Casdoor 提供 Discovery，会自动发现 |
| `OIDC_DISCOVERY_URL` | 留空（自动 `${OIDC_ISSUER}/.well-known/openid-configuration`） | [Standard OIDC client](https://casdoor.org/docs/how-to-connect/oidc-client) |
| `OIDC_USE_USERINFO` | **保持 `true`（默认）** | 见 F3，角色映射依赖它 |
| `OIDC_END_SESSION_ENDPOINT` | 留空（自动发现为 `https://id.hamkit.click/api/logout`） | [`object/wellknown_oidc_discovery.go`](https://github.com/casdoor/casdoor/blob/master/object/wellknown_oidc_discovery.go) |
| `TRUST_PROXY` | 前面有几层反代就填几（Nginx 单层 `1`） | 已有默认 |

### F2. 回调地址登记位置（关键，别填错地方）

**回调地址固定为 `${BASE_URL}/auth/callback`，即 `https://hamkit.click/auth/callback`。登记位置：Casdoor 控制台 → 应用（Application）→ Redirect URLs 加入该地址**（对应 `Application.RedirectUris`）([`object/application.go`](https://github.com/casdoor/casdoor/blob/master/object/application.go)、[Application configuration](https://casdoor.org/docs/application/config))。

- **⚠️ 不要填到 Provider 的 callback**：官方明确区分 —— Provider 里的 callback 是 **Casdoor 自己的** `/callback`，应用里的 Redirect URL 才是**你应用的**回调；此处要改的是后者（[Application configuration](https://casdoor.org/docs/application/config)）。
- **格式硬约束（源码行为，[`object/application_util.go`](https://github.com/casdoor/casdoor/blob/master/object/application_util.go)）**：**路径必须完全相等、不支持通配**；scheme 与端口必须一致（loopback 例外）；主机支持子域后缀匹配。故 `https://hamkit.click/auth/callback` 可覆盖 `www.hamkit.click`（子域后缀匹配）但**路径须相同**；本地调试 `http://localhost:8787/auth/callback` **必须另加精确项**（`http`/`https` 不互通）。
- **RP-Initiated Logout 复用这份列表**：HamKit 若传 `post_logout_redirect_uri`，该地址也必须已在 `redirectUris` 中，否则 Casdoor 报 `Redirect URI ... doesn't exist in the allowed Redirect URI list`（[`controllers/account.go`](https://github.com/casdoor/casdoor/blob/master/controllers/account.go)）。

### F3. 角色映射（`OIDC_ROLE_CLAIM` + `OIDC_ROLE_MAP`）—— 这里有坑

**先看 HamKit 侧的真实行为**：① [`server/core/oidc.mjs`](<../website/server/core/oidc.mjs>) 中 profile 合并为 `profile = { ...ui, ...claims }` —— **ID Token claims 覆盖 userinfo**；② [`server/store/repositories.mjs`](<../website/server/store/repositories.mjs>) `normalizeUserOidc()` 读取 `claims[config.oidc.roleClaim || 'roles']`，**只接受字符串数组**或逗号/空白分隔的字符串：

```js
const rolesClaim = claims[config?.oidc?.roleClaim || 'roles'];
const idpRoles = Array.isArray(rolesClaim)
  ? rolesClaim
  : typeof rolesClaim === 'string'
    ? rolesClaim.split(/[\s,]+/).filter(Boolean)
    : [];
```

随后 `resolveRole()` 用 `cfg.oidc.roleMap[String(idpRole).toLowerCase()]` 逐个查表。

> **关键事实**：Casdoor 默认 `TokenFormat = JWT` 时 ID Token 的 `roles` 是**对象数组**（`[]*Role`）。由于 HamKit 让 ID Token 覆盖 userinfo，`Array.isArray(rolesClaim)` 为真 → `idpRoles` 变成一串对象 → `String(idpRole)` 得 `"[object Object]"` → **`OIDC_ROLE_MAP` 永远匹配不上**。**默认配置下角色映射不可用。**

**方案 A（推荐）：Casdoor 应用改 `JWT-Custom`，用 tokenAttributes 输出角色名字符串数组。**
配置：Token 格式选 **`JWT-Custom`**；Token 属性（`tokenAttributes`）加一项 —— `name` = **`roles`**、`category` = **`Existing Field`**、`value` = **`Roles`**。这样 `getClaimsCustom()` 走 `getUserFieldValue()` 的 `case "Roles"` → `getUserRoleNames()` → **`[]string` 纯角色名**（如 `["hamkit-admin"]`）；因 HamKit `roleClaim` 默认 `roles`，**`OIDC_ROLE_CLAIM` 保持 `roles` 即可**；同理可加 `permissions` ← `Permissions` 或 `permissionNames`。`JWT-Custom` 仍固定包含 `iss`/`sub`/`aud`/`exp`/`nonce`/`scope`，不影响 OIDC 校验。HamKit 侧：`OIDC_ROLE_CLAIM=roles`、`OIDC_ROLE_MAP=<casdoor角色名>:admin,...`（key 会被 `toLowerCase()`，Casdoor 角色名含大写时要写小写形式）。出处：[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)、[`object/user_util.go`](https://github.com/casdoor/casdoor/blob/master/object/user_util.go)、[`object/application.go`](https://github.com/casdoor/casdoor/blob/master/object/application.go)。前提/代价：`JWT-Custom` 会**丢掉** `JWT` 默认携带的其他用户字段（只保留所列字段 + 固定标准字段）；HamKit 保留 `profile email` scope 且 `OIDC_USE_USERINFO=true` 可从 userinfo 补齐 `name`/`email`/`picture`（同源无冲突）。⚠️ 未能确认控制台中 Token 格式/Token 属性的确切 UI 文案（见 D15）；若找不到 `Existing Field` 类别，可退化为用 Casdoor 配置 API 直接写 `tokenFormat`/`tokenAttributes`（字段名已由源码确认）。

**方案 B（不推荐，workaround）：用 `tag` 字段当角色 claim。** 默认 `JWT` 下 `tag` 是**字符串** claim（来自 `User.Tag`，源码注释自称 "workaround for custom claim by reusing `tag`"）。把用户 Tag 设为角色名（单值），HamKit 设 `OIDC_ROLE_CLAIM=tag`，因是字符串会被 split 成数组从而匹配。局限：只会得到一个值，且 `tag` 语义是"用户标签"，易与其他用途冲突。出处：[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)。

**方案 C：绕开 IdP 角色，用 `BOOTSTRAP_ADMINS` / 邮箱域名白名单。** HamKit 的 `resolveRole()` 优先级为 `roleLocked` → **`BOOTSTRAP_ADMINS`** → `OIDC_ROLE_MAP` → 既有角色 → `FIRST_USER_IS_ADMIN` → `viewer`，故**即使角色映射完全不可用**，也可用 `BOOTSTRAP_ADMINS=you@example.com`（匹配 email / username / sub，均 `toLowerCase()` 比较）直接把指定账号提为 admin，配合 `ALLOWED_EMAIL_DOMAINS` 收窄登录面。出处：[`server/store/repositories.mjs`](<../website/server/store/repositories.mjs>)；配置项见 [`.env.example`](<../website/.env.example>)。前提：HamKit 能从 Casdoor 拿到 `email`（需 `email` scope 且用户有邮箱）与 `sub`（Casdoor 的 `sub` 是 `user.Id` UUID，不是用户名）。

**方案 D：只靠 Casdoor 的 `isAdmin` 布尔 claim（需改 HamKit 代码，当前不支持）。** 默认 `JWT` 及 `JWT-Custom`（把 `isAdmin` 加入 `tokenFields`）都能提供 `isAdmin: true/false`，但 HamKit 的 `normalizeUserOidc()` 只处理数组/字符串角色，**不认识布尔 claim**；采用需改 HamKit 代码。出处：[`object/token_jwt.go`](https://github.com/casdoor/casdoor/blob/master/object/token_jwt.go)、[`server/store/repositories.mjs`](<../website/server/store/repositories.mjs>)。

### F4. 最小可用配置清单与验证顺序

**Casdoor 侧**：① `conf/app.conf` 设 `origin = https://id.hamkit.click`（并设 `runmode = prod`、按生产填数据库与 `redisEndpoint`）；② 新建/编辑应用，记下 Client ID / Client Secret；③ **Redirect URLs** 加 `https://hamkit.click/auth/callback`（本地调试另加 `http://localhost:8787/auth/callback`）；④ **Token 格式**改 `JWT-Custom`，**Token 属性**加 `roles` ← `Existing Field` = `Roles`（方案 A）；⑤ 建 Role（`hamkit-admin`/`hamkit-editor`/`hamkit-viewer`）并把用户加入对应 Role；⑥ 改掉 `built-in/admin` 的默认密码。

**HamKit 侧 `.env`**：

```dotenv
BASE_URL=https://hamkit.click
OIDC_ISSUER=https://id.hamkit.click
OIDC_CLIENT_ID=<Casdoor 应用 Client ID>
OIDC_CLIENT_SECRET=<Casdoor 应用 Client Secret>
OIDC_CLIENT_AUTH_METHOD=client_secret_basic
OIDC_SCOPE=openid profile email
OIDC_ROLE_CLAIM=roles
OIDC_ROLE_MAP=hamkit-admin:admin,hamkit-editor:editor,hamkit-viewer:viewer
TRUST_PROXY=1
FIRST_USER_IS_ADMIN=true
# 兜底（可选）
# BOOTSTRAP_ADMINS=you@example.com
```

**验证顺序**：先 `curl -s https://id.hamkit.click/.well-known/openid-configuration` 确认 `issuer` 与 `OIDC_ISSUER` **完全一致**；再走一遍登录，观察 HamKit 是否返回 `idpRoles`（[`server/routes/api.mjs`](<../website/server/routes/api.mjs>) 会输出 `idpRoles`）；若 `idpRoles` 为空但 userinfo 可见 roles，说明 ID Token 的 `roles` 形态没改对（回到方案 A）。

## 附：明确的「未能确认」清单

| # | 未能确认的内容 | 尝试过的途径 |
|---|---|---|
| 1 | Docker Hub 是否有官方 `-slim`/`-alpine`/`-rootless` tag 变体 | 列出 `casbin/casdoor`（50 个）与 `casbin/casdoor-all-in-one`（15 个）tag 名，无非版本号 tag |
| 2 | `-e GIN_MODE=release` 是否有效 | 全仓库 grep `GIN_MODE`：仅官方 Docker 文档出现，源码为 Beego 无此键；判为疑似文档残留 |
| 3 | 官方跨大版本升级/迁移指南 | 探测 `casdoor.org/docs/deployment/upgrade` 等路径均 404；仓库无 `UPGRADE.md`/`MIGRATION.md` |
| 4 | 应用时区配置项 | grep `conf/`+`util/` 的 `TZ`/`time.LoadLocation`/`Local`，仅见硬编码 `time.Local()`；镜像装了 `tzdata` 但无文档化配置键 |
| 5 | 控制台中 Token 格式 / Token 属性（`Existing Field`）的确切 UI 文案与入口 | 抓取 [Application configuration](https://casdoor.org/docs/application/config)，不含 TokenFormat UI 说明；结论由源码结构体与 API 字段名推出 |
| 6 | `sessionOn` 配置项 | grep 全仓库仅见硬编码 `SessionOn = true`，无配置读取；当前 `app.conf` 与官方文档均无此键（仅 Helm values 模板残留），判为**不生效** |
| 7 | `casbin/casdoor` 的 Docker Hub `full_description` | Registry API 返回空字符串，无法引用官方镜像说明正文 |
| 8 | GitHub Releases 的 release notes 正文 | GitHub REST API 对未认证请求返回 403；改用网页版 release 页与 `expanded_assets` 获取 tag 与资产名 |
