# HamKit 本地发版手册（不用 GitHub Actions）

> 结论：**发布走本地脚本 + 官网后台登记，不依赖 GitHub Actions。**
> GitHub 只保留两件事：① CI 跑测试（`.github/workflows/ci.yml`、`test.yml`）；② Release 作为留档与 App 的**回退**更新源。
> 真正的分发链路是：**S3 → CloudFront（apk.hamkit.click）→ 官网 data/versions.json → App 读 hamkit.click/update.json**。

---

## 一、一条命令发版

```bat
:: 正式发版（自动决定 versionCode → 跑单测 → 打包 → 验签 → 上传 → 生成官网发布载荷）
scripts\release.cmd 3.0.1 -Changelog docs\UPDATE_v3.0.1.md

:: 先演练：只校验已有产物，不联网、不改文件
scripts\release.cmd 3.0.1 -SkipBuild -DryRun

:: 只打包上传，不登记 GitHub Release / 不打 tag
scripts\release.cmd 3.0.1 -SkipInvalidate
```

脚本本体是 [`scripts/release.ps1`](../scripts/release.ps1)（`release.cmd` 只是加 `-ExecutionPolicy Bypass` 的包装，
避免每次和 PowerShell 执行策略纠缠）。常用开关：

| 开关 | 作用 |
| --- | --- |
| `-VersionCode N` | 手动指定 versionCode（默认自动：`max(仓库, 线上) + 1`，若仓库已把该版本准备好则沿用） |
| `-Changelog <路径>` | 更新日志 Markdown（默认 `docs/UPDATE_v<版本>.md`） |
| `-SkipTests` / `-SkipBuild` | 跳过单测 / 跳过构建（用已有产物） |
| `-DryRun` | 只校验与生成载荷：不改 `build.gradle.kts`、不上传、不碰 git |
| `-SkipUpload` / `-SkipInvalidate` | 只打包不上传 / 上传但不刷 CloudFront |
| `-CommitVersion` / `-Tag` / `-CreateGitHubRelease` | 提交版本号 / 打 tag / 建 GitHub Release |
| `-LiveVersionCode N` | 网络与 S3 都读不到线上版本时，手工给出比对基准 |
| `-JavaHome <路径>` | 指定 JDK（默认自动探测） |

产物与记录：

- APK：`app/build/outputs/apk/release/HamKit_<版本>_<versionCode>-release.apk`
- 构建日志：`build/release/release-<版本>-<versionCode>.log`
- 发布载荷：`build/release/publish-<版本>.json`（含 versionCode / downloadUrl / sizeBytes / sha256 / changelog）

## 二、脚本里的三道硬门槛（每一条都对应一次真实事故）

| # | 门槛 | 为什么必须有 |
| --- | --- | --- |
| 1 | **签名指纹必须等于 `signing/release.keystore`**（用 `apksigner verify --print-certs` 比对，构建前查 keystore、构建后查产物） | `app/build.gradle.kts` 在缺 `KEYSTORE_*` 环境变量时**直接让 release 构建失败**，不再回退 debug keystore。历史上 v2.1.1 就因此出现「签名不一致，请卸载后重装」——老用户被迫清数据。 |
| 2 | **versionCode 必须严格大于线上**（`update.json` 与 S3 产物名双源取最大值） | App 只按 versionCode 判断是否需要更新。发了但号没涨，App 会被告知「已是最新」，等于白发一版。 |
| 3 | **产物 metadata 的版本必须等于本次目标版本** | 仓库里躺着 `HamKit_3.0.1_17` 与 `HamKit_3.0.1_18` 两个**从未发布**的 APK（2026-08-17 / 08-30 构建）。这类「打包了但没发出去」的产物一旦被当作本次发版对象，就会发错包。 |

## 三、基础设施（脚本默认值的事实来源）

| 项 | 值 |
| --- | --- |
| APK bucket | `hamkit-apk-tpe`（ap-east-2），对象前缀 `release/`，已开版本控制（HTTP 源站） |
| CloudFront | `E3OUERALJ56FWM` → 别名 `apk.hamkit.click` → 源 `hamkit-apk-tpe.s3.ap-east-2.amazonaws.com` |
| 上传时的元数据 | `Content-Type: application/vnd.android.package-archive`，另附 `sha256=<哈希>` 便于事后核对 |
| 官网服务器 | nginx 静态 + Node（`/srv/hamkit/website`），版本数据在服务器的 `data/versions.json` |
| App 更新入口 | `https://hamkit.click/update.json`（由 `data/versions.json` 派生；`stable` 渠道） |
| App 回退源 | GitHub Releases（`Downloader.kt`：先官网，失败再走 GitHub） |

> ⚠️ **仓库里的 `website/update.json` 不是真源**：它是 2.1.2/15 的陈旧副本，线上已经是 3.0.0/16。
> 真源是服务器上的 `data/versions.json`，`/update.json` 由它派生。
> 用仓库副本覆盖服务器文件会把线上版本**回退**到 2.1.2 —— 如果哪天要重新部署整站，先用后台确认版本数据仍在。

> ⚠️ **`docs/CASDOOR_DEPLOYMENT_GUIDE.md` 的回滚说明已过期**：它写「东京桶 `hamkit-apk` 仍完整保留，回滚只需切回源」，
> 实测该桶**已不存在**（`NoSuchBucket`）。现在只有台北桶，回滚只能依赖桶的版本控制 + CloudFront 失效。

## 四、上传之后：在官网后台登记版本（这一步决定 App 能否看到更新）

1. 打开 <https://hamkit.click/admin/> → **版本管理** → 新增版本；
2. 字段从 `build/release/publish-<版本>.json` 复制：

   | 后台字段 | 取值 |
   | --- | --- |
   | `versionCode` | 脚本输出（必须与 APK 一致） |
   | `versionName` | 例如 `3.0.1` |
   | `channel` | `stable`（只有 stable 会写进 `/update.json`） |
   | `tag` | `v3.0.1` |
   | `downloadUrl` | `https://apk.hamkit.click/release/HamKit_3.0.1_18-release.apk` |
   | `sizeBytes` / `sha256` | 脚本输出 |
   | `changelog` | `publish-*.json` 里的 `changelog` 字段（Markdown，后台可预览 App 会拉到什么） |

3. 校验生效：

   ```powershell
   curl.exe -s https://hamkit.click/update.json      # versionCode 应为新版本
   ```

4. App 侧：**设置 → 检查更新**，应提示新版本。

## 五、出问题怎么退

| 症状 | 处置 |
| --- | --- |
| APK 传错了 / 传坏了 | 桶开了版本控制：`aws s3api list-object-versions --bucket hamkit-apk-tpe --prefix release/<文件名>`，把上一个版本 `copy-object` 回去，再刷 CloudFront |
| CDN 还是旧文件 | `aws cloudfront create-invalidation --distribution-id E3OUERALJ56FWM --paths "/release/<文件名>"` |
| 官网推了坏版本 | 后台版本管理里删除该版本，`/update.json` 自动回退到上一个 stable（这是后台的设计行为） |
| 老用户装不上（签名不一致） | 只可能是门槛 1 被绕过。**不要**让用户卸载重装；用 `apksigner verify --print-certs` 对比线上 APK 与本地 keystore 指纹，把用错 key 的那版撤下 |

## 六、环境注意事项

- **⚠️ 内存不够时构建会被杀（本机 15 GB 内存实测）**：`org.gradle.jvmargs=-Xmx4096m` 加上
  `org.gradle.parallel=true`，再叠加一个 Kotlin 编译 daemon，在可用内存只剩 3~4 GB 时会
  被系统直接杀掉（表现是命令「无输出地终止」，日志戛然而止）。绕过办法：
  ```powershell
  $env:GRADLE_OPTS='-Dorg.gradle.parallel=false -Dorg.gradle.jvmargs=-Xmx2560m'
  cmd /c "scripts\release.cmd 4.0.0 -VersionCode 19 -Changelog docs\UPDATE_v4.0.0.md"
  ```
  单测可以先单独跑完再用 `-SkipTests` 跳过（脚本里的单测步骤同样受内存限制影响）。
- **⚠️ 脚本会把「构建成功」误报成失败**：`Invoke-Gradle` 用 `& $gradlew @Arguments 2>&1 |
  Tee-Object` 取 `$LASTEXITCODE`，在 PowerShell 里经管道后该值可能不等于 gradle 的真实退出码。
  实测出现过 `BUILD SUCCESSFUL` 但脚本报「构建失败」并中止（因此**没有上传**）。
  排查方法：直接看 `build/release/release-<版本>-<code>.log` 的末尾，
  再手工确认产物与签名，然后手工上传（见 §七）。
- **JDK**：本机 `JAVA_HOME` 指向 Liberica **JRE**（无 `javac`），Gradle 需要 JDK。
  脚本会自动改用 `C:\Program Files\Android\Android Studio\jbr`；想固定就设 `JAVA_HOME` 或传 `-JavaHome`。
- **TLS**：Windows PowerShell 5.1 访问 `https://hamkit.click` 可能握手失败（脚本会告警并回退到
  `aws s3 ls` 读取线上最高 versionCode）。曲线排查：`curl.exe -sI https://hamkit.click/update.json`。
- **签名凭据只从环境变量读取**：`KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。
  `scripts/release.ps1` 不再读取 `signing/github-secrets.txt` 等明文文件；请把口令放进密码管理器或 CI Secrets。
  确需轮换口令时用 `keytool -storepasswd` / `-keypasswd`——**只改口令，不动密钥对**，因此不影响用户升级。
  `signing/github-secrets.txt` 若仍存在，建议迁移后删除。
- **AWS 身份**：当前 `aws sts get-caller-identity` 返回的是账号 **root**。上传 APK 建议改用只对该桶有写权限的
  IAM 用户（参考服务器侧已有的 `hamkit-tle-sync-policy-tpe` 思路），避免 root 长期留在本机凭据里。

## 七、发版前 30 秒自查

- [ ] `git status` 干净、分支正确（`miuix`），要发的内容都已提交
- [ ] 环境变量包含 `KEYSTORE_*` 与 `AMAP_SDK_KEY`（高德 Android SDK Key，按包名 + SHA-1 限制）
- [ ] `docs/UPDATE_v<版本>.md` 已写好（脚本会把它带进发布载荷与 GitHub Release）
- [ ] `scripts\release.cmd <版本> -DryRun -SkipBuild` 通过（签名指纹、版本单调性都对）
- [ ] 上传后 `https://apk.hamkit.click/release/<文件名>` 能下载且大小一致
- [ ] 官网后台登记完成，`/update.json` 已变
- [ ] QQ 群 / HamCQ 帖子同步发公告

### 脚本中止在构建步骤时的手工补发（2026-10-11 实操过一遍）

若脚本误报构建失败但其实 `BUILD SUCCESSFUL`，按下面四步手工补完，**不要重复构建**：

```powershell
# 1) 确认产物与元数据
Get-ChildItem app\build\outputs\apk\release\*.apk
Get-Content app\build\outputs\apk\release\output-metadata.json   # versionCode/versionName 必须对

# 2) 核签名指纹（必须等于 release.keystore 的 dc:61:ff:...:0b:37）
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs `
  app\build\outputs\apk\release\HamKit_<版本>_<code>-release.apk

# 3) 上传（带上 sha256 元数据）
$apk = 'app\build\outputs\apk\release\HamKit_<版本>_<code>-release.apk'
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
aws s3 cp $apk s3://hamkit-apk-tpe/release/HamKit_<版本>_<code>-release.apk `
  --content-type application/vnd.android.package-archive --metadata "sha256=$sha"

# 4) 刷 CloudFront 并用 CDN 校验下载哈希与本地一致
aws cloudfront create-invalidation --distribution-id E3OUERALJ56FWM `
  --paths "/release/HamKit_<版本>_<code>-release.apk"
curl.exe -sS -o "$env:TEMP\v.apk" -w "%{http_code} %{size_download}`n" `
  "https://apk.hamkit.click/release/HamKit_<版本>_<code>-release.apk"
(Get-FileHash "$env:TEMP\v.apk" -Algorithm SHA256).Hash.ToLower()   # 应与第 3 步一致
```
