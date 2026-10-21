# 高德 Key 与敏感信息处理

> 2026-10-21 调整：按项目要求，**地图 SDK 与 Web 服务（天气/逆地理编码）全部使用高德**。
> 2026-10-19 的“三碎片 + AES-256-GCM 加密 secrets.dat”方案已移除：它在公开仓库/APK 中可完整还原，
> 属于混淆而非加密，不能保护任何真正的秘密。

## 一、当前方案

| 用途 | 高德服务 | Key 来源 | 是否秘密 |
|---|---|---|---|
| 地图显示 | Android 地图 SDK | `AMAP_SDK_KEY` 或 `local.properties` 的 `amap.sdk.key`，写入 manifest | 否（客户端标识） |
| 天气 / 逆地理编码 | Web 服务 API | `AMAP_WEB_KEY` 或 `local.properties` 的 `amap.api.key`，构建时写入 `BuildConfig.AMAP_WEB_KEY` | 否（随 APK 分发，可被提取） |

- 不再有 `secrets.dat`、`SecretManager`、`encryptSecrets` 任务。
- 两个 Key 都随 APK 分发；**不要把它们当秘密**，也不要用客户端“加密”去保护它们。
- 真正的服务端秘密（如果以后有）只放服务端环境变量。

## 二、构建时注入

`app/build.gradle.kts`：

```kotlin
// Android SDK Key → AndroidManifest meta-data
val amapSdkKey = System.getenv("AMAP_SDK_KEY")?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("amap.sdk.key", "")
manifestPlaceholders["AMAP_SDK_KEY"] = amapSdkKey

// Web 服务 Key → BuildConfig
val amapWebKey = System.getenv("AMAP_WEB_KEY")?.takeIf { it.isNotBlank() }
    ?: localProps.getProperty("amap.api.key", "")
val escapedAmapWebKey = amapWebKey.replace("\\", "\\\\").replace("\"", "\\\"")
buildConfigField("String", "AMAP_WEB_KEY", "\"$escapedAmapWebKey\"")
```

- 本地开发：`local.properties` 设置 `amap.sdk.key` 与 `amap.api.key`（该文件已被 `.gitignore` 忽略）。
- CI/发布：设置 `AMAP_SDK_KEY` 与 `AMAP_WEB_KEY` 环境变量/Secrets。
- 缺 Web Key 时天气会报 `ApiKeyMissingException`，不会发出无效请求。

## 三、高德控制台要做的事

1. Android SDK Key 绑定 **包名 `com.example.hamkit`** 与 **release 证书 SHA-1**；
2. 不要绑定 debug 证书（debug key 是公开的）；
3. Web 服务 Key 无法绑定 Android 包名，只能依赖：
   - 控制台配额/用量告警；
   - 定期检查调用来源；
   - 发现异常立即轮换；
4. 如果同一个 Key 也被其他应用/网站使用，拆分为独立 Key，便于定位和限额。

> 绑定包名/SHA-1 不能阻止逆向者用“同包名同签名”的伪造包，但能阻止绝大多数直接复用。
> Web Key 没有这层保护，泄露就是泄露；这是选择“客户端直连高德 Web 服务”的固有代价。

## 四、天气与逆地理编码

`WeatherApiService` 调用：

- 逆地理编码：`https://restapi.amap.com/v3/geocode/regeo`
- 实时天气：`https://restapi.amap.com/v3/weather/weatherInfo?extensions=base`
- 4 天预报：`https://restapi.amap.com/v3/weather/weatherInfo?extensions=all`

请求包含经纬度与 Web Key；不经过开发者服务器。高德按自己的隐私政策处理这些请求。

## 五、旧 Key 与历史

- 如果继续使用现有 Key，可以不为本次改造生成新 Key。
- 但旧 Key 可能已进入公开 git 历史；继续沿用意味着接受被他人复用的风险。
- 至少要做：包名/SHA-1 限制（SDK Key）、配额监控、异常调用告警、必要时轮换。
- 若清理历史，用 `git filter-repo`/BFG 删除 `secrets.dat`、旧文档明文 Key 等内容；仅删除当前文件不够。

## 六、规则

> **能在客户端拿到的 Key，就不是秘密。**
> 客户端直连第三方 API 时，只能靠配额、监控、包名/签名限制降低风险；需要真正保密就必须走服务端代理。

相关文件：

- `app/build.gradle.kts`：`AMAP_SDK_KEY` / `AMAP_WEB_KEY` 注入、release 签名门槛
- `app/src/main/java/com/example/hamkit/data/weather/WeatherApiService.kt`：高德天气/逆地理编码
- `app/src/main/java/com/example/hamkit/data/location/AmapGeocodeService.kt`：地址反向地理编码
- `app/src/main/java/com/example/hamkit/data/privacy/AmapPrivacyInitializer.kt`：高德隐私合规初始化
- `.github/workflows/ci.yml`：CI Key/签名注入
