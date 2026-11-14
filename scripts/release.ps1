<#
.SYNOPSIS
  HamKit 本地一键发版（打包 → 验签 → 上传 CDN → 生成官网发布载荷）。

.DESCRIPTION
  替代 GitHub Actions 的纯本地发布流程。GitHub 侧只保留「记录 + App 回退源」，
  真正的分发走官网服务器（hamkit.click/update.json）与 CDN（apk.hamkit.click）。

  它做的事（严格顺序，每步失败即中止）：
    0) 预检：JDK / apksigner / aws 可用；keystore 密码可读；签名指纹已知
    1) 版本号解析与单调性校验（必须 > 线上 versionCode，否则更新检查会认为「已是最新」）
    2) 写入根 build.gradle.kts 的 managerVersionCode / managerVersionName
    3) :app:testDebugUnitTest（可 -SkipTests）
    4) :app:assembleRelease（注入 release keystore 环境变量）
    5) 产物校验：output-metadata.json 的 versionCode/versionName + apksigner 证书指纹
       —— 指纹不等于 signing/release.keystore 时直接失败（防止静默用 debug key 签名）
    6) 上传 s3://hamkit-apk-tpe/release/ 并回读校验大小
    7) CloudFront 失效（默认 apk.hamkit.click 对应的 E3OUERALJ56FWM）
    8) 生成官网发布载荷 build/release/publish-<版本>.json（贴进 /admin/ 或后续走 API）
    9) 可选：gh release（留档 + App 的 GitHub 回退源）、git commit / tag

.EXAMPLE
  # 只做校验与载荷生成，不动网络、不改文件（推荐先跑这个）
  pwsh scripts/release.ps1 -VersionName 3.0.1 -SkipBuild -DryRun

.EXAMPLE
  # 正式发版：自动 versionCode = max(仓库, 线上) + 1
  pwsh scripts/release.ps1 -VersionName 3.0.1 -Changelog docs/UPDATE_v3.0.1.md

.EXAMPLE
  # 全自动（含 GitHub Release 与 git tag）
  pwsh scripts/release.ps1 -VersionName 3.0.1 -Changelog docs/UPDATE_v3.0.1.md -CommitVersion -Tag -CreateGitHubRelease
#>
[CmdletBinding()]
param(
    # 语义化版本名，例如 3.0.1
    [Parameter(Mandatory = $true)][string]$VersionName,

    # 目标 versionCode；0 = 自动取 max(仓库当前, 线上 update.json) + 1
    [int]$VersionCode = 0,

    # 更新日志 Markdown 路径；默认 docs/UPDATE_v<版本>.md
    [string]$Changelog,

    # 只校验「已有产物」，跳过构建（仍会读 output-metadata.json 并验签）
    [switch]$SkipBuild,
    [switch]$SkipTests,

    # 只读演练：不改 build.gradle.kts、不上传、不失效、不碰 git
    [switch]$DryRun,

    [switch]$SkipUpload,
    [switch]$SkipInvalidate,

    # 额外动作
    [switch]$CreateGitHubRelease,
    [switch]$CommitVersion,
    [switch]$Tag,

    # 基础设施（默认值取自 docs/CASDOOR_DEPLOYMENT_GUIDE.md 与仓库现状）
    [string]$Bucket = 'hamkit-apk-tpe',
    [string]$DistributionId = 'E3OUERALJ56FWM',
    [string]$CdnBase = 'https://apk.hamkit.click',
    [string]$UpdateUrl = 'https://hamkit.click/update.json',

    # 凭据只从环境变量读取；不再读取 signing/github-secrets.txt 之类的明文文件
    [string]$KeystorePath,

    # 期望的签名证书 SHA-256；留空则从 keystore 现场推导
    [string]$ExpectedCertSha256,

    # JDK：留空则自动探测（JAVA_HOME 必须是 JDK，不能是 JRE）
    [string]$JavaHome,

    # 线上 versionCode 兜底值：网络与 S3 都读不到时用它做单调性校验（0 = 不指定）
    [int]$LiveVersionCode = 0
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 默认不启用 TLS 1.2，会让 https://hamkit.click 握手直接失败
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

# ────────────────────────────────────────────────────────────── 输出辅助 ──
$script:StepNo = 0
function Step([string]$Text) {
    $script:StepNo++
    Write-Host ''
    Write-Host ("[{0}] {1}" -f $script:StepNo, $Text) -ForegroundColor Cyan
}
function Info([string]$Text) { Write-Host "    $Text" }
function Warn([string]$Text) { Write-Host "  ! $Text" -ForegroundColor Yellow }
function Die([string]$Text) { Write-Host "  x $Text" -ForegroundColor Red; exit 1 }
function Ok([string]$Text) { Write-Host "  v $Text" -ForegroundColor Green }

# 写 UTF-8 无 BOM（Windows PowerShell 5.1 的 Set-Content -Encoding UTF8 会带 BOM，
# 会让 build.gradle.kts 与 JSON 出现意外的 \uFEFF 前缀）
function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)
    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $enc = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

# 调用外部程序并安全捕获两个输出流（PS 5.1 在 ErrorActionPreference=Stop 下
# 会把原生命令写到 stderr 的进度信息当成终止错误）
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & $Exe @Arguments 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return [pscustomobject]@{ Output = @($out); ExitCode = $code }
}

# ──────────────────────────────────────────────────────────── 路径与根 ──
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$RootGradle = Join-Path $RepoRoot 'build.gradle.kts'
$AppDir = Join-Path $RepoRoot 'app'
$OutputDir = Join-Path $AppDir 'build\outputs\apk\release'
$OutputMeta = Join-Path $OutputDir 'output-metadata.json'
$BuildDir = Join-Path $RepoRoot 'build\release'

if (-not (Test-Path $RootGradle)) { Die "未找到根 build.gradle.kts，脚本必须在仓库内运行（当前 $RepoRoot）" }
if (-not $Changelog) { $Changelog = Join-Path $RepoRoot ("docs\UPDATE_v{0}.md" -f $VersionName) }

Step "预检：工具链与凭据"
Info "仓库根目录：$RepoRoot"

# --- JDK：Gradle 需要 JDK（javac），而本机 JAVA_HOME 可能指向 JRE ---
function Resolve-JavaHome {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += @(
        'C:\Program Files\Android\Android Studio\jbr',
        'C:\Program Files\Java\jdk-21',
        'C:\Program Files\Eclipse Adoptium\jdk-21*'
    )
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $expanded = Get-ChildItem -Path $c -ErrorAction SilentlyContinue -Directory | Select-Object -First 1 -ExpandProperty FullName
        foreach ($p in @($c, $expanded)) {
            if ($p -and (Test-Path (Join-Path $p 'bin\javac.exe'))) { return $p }
        }
    }
    return $null
}
$resolvedJava = Resolve-JavaHome -Explicit $JavaHome
if (-not $resolvedJava) { Die "找不到可用 JDK（需要 bin\javac.exe）。用 -JavaHome 指定，或安装 JDK 21。" }
if ($env:JAVA_HOME -and ($resolvedJava -ne $env:JAVA_HOME.TrimEnd('\'))) {
    Warn "JAVA_HOME 指向的不是 JDK，本次改用：$resolvedJava"
}
$env:JAVA_HOME = $resolvedJava
Info "JAVA_HOME = $resolvedJava"

# --- Android SDK 与 apksigner ---
$localProps = Join-Path $RepoRoot 'local.properties'
$sdkDir = $null
if (Test-Path $localProps) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($line) {
        $sdkDir = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
        $sdkDir = $sdkDir -replace '\\:', ':' -replace '\\\\', '\'
    }
}
if (-not $sdkDir -or -not (Test-Path $sdkDir)) {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { $sdkDir = $env:ANDROID_HOME }
}
if (-not $sdkDir -or -not (Test-Path $sdkDir)) { Die "找不到 Android SDK（local.properties 的 sdk.dir 或 ANDROID_HOME）" }

$apksigner = Get-ChildItem (Join-Path $sdkDir 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apksigner) { Die "找不到 apksigner（$sdkDir\build-tools\<版本>\apksigner.bat）" }
Info "apksigner = $apksigner"

# --- 凭据：只从环境变量读取，避免密码与 keystore 同目录明文落盘 ---
if (-not $KeystorePath) {
    $KeystorePath = $env:KEYSTORE_PATH
    if (-not $KeystorePath) { $KeystorePath = Join-Path $RepoRoot 'signing\release.keystore' }
}
if (-not (Test-Path $KeystorePath)) { Die "找不到 release keystore：$KeystorePath" }

$storePass = $env:KEYSTORE_PASSWORD
$keyAlias = $env:KEY_ALIAS
$keyPass = $env:KEY_PASSWORD
if (-not $storePass -or -not $keyAlias -or -not $keyPass) {
    Die @"
缺少签名凭据（KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）。
  * 请在当前会话设置环境变量，或使用 CI/GitHub Secrets 注入。
  * release.ps1 不再读取 signing/github-secrets.txt 等明文文件。
  * 缺少凭据时 Gradle 现在会直接失败，不会静默改用 debug key。
"@
}
Info "keystore = $KeystorePath"

# --- 期望签名指纹：显式给出，或从 keystore 现场推导 ---
$keytool = Join-Path $resolvedJava 'bin\keytool.exe'
if (-not (Test-Path $keytool)) { Die "找不到 keytool：$keytool" }
if (-not $ExpectedCertSha256) {
    $kt = Invoke-Native -Exe $keytool -Arguments @('-list', '-v', '-keystore', $KeystorePath, '-storepass', $storePass, '-alias', $keyAlias)
    $m = [regex]::Match(($kt.Output -join "`n"), 'SHA256:\s*([0-9A-Fa-f:]{95})')
    if (-not $m.Success) { Die "无法从 keystore 读取证书指纹（密码或别名是否正确？）：$($kt.Output -join ' ')" }
    $ExpectedCertSha256 = ($m.Groups[1].Value -replace ':', '').ToLower()
    $ExpectedCertSha256 = ($ExpectedCertSha256 -replace '(..)(?=.)', '$1:')
}
$ExpectedCertSha256 = $ExpectedCertSha256.Trim().ToLower()
Info "期望签名指纹 = $ExpectedCertSha256"

# --- aws / gh ---
if (-not $SkipUpload -and -not $DryRun) {
    if (-not (Get-Command aws -ErrorAction SilentlyContinue)) { Die "找不到 aws CLI" }
    $identity = Invoke-Native -Exe 'aws' -Arguments @('sts', 'get-caller-identity')
    if ($identity.ExitCode -ne 0) { Die "aws 凭据不可用：$($identity.Output -join ' ')" }
}
if ($CreateGitHubRelease -and -not (Get-Command gh -ErrorAction SilentlyContinue)) { Die "找不到 gh CLI" }
Ok '预检通过'

# ─────────────────────────────────────────────────────── 版本号解析与校验 ──
Step "解析版本号"
$gradleText = Get-Content $RootGradle -Raw
$mCode = [regex]::Match($gradleText, 'managerVersionCode\s+by\s+extra\((\d+)\)')
$mName = [regex]::Match($gradleText, 'managerVersionName\s+by\s+extra\("([^"]*)"\)')
if (-not $mCode.Success -or -not $mName.Success) { Die "无法从 build.gradle.kts 解析 managerVersionCode / managerVersionName" }
$currentCode = [int]$mCode.Groups[1].Value
$currentName = $mName.Groups[1].Value
Info "仓库当前：$currentName ($currentCode)"

# 线上版本：update.json（用户端真正读到的）与 S3 上已有产物名（更严格）取较大者。
# 目的只有一个 —— 保证新 versionCode 大于用户手上可能已有的任何版本，
# 否则 App 的更新检查会认为「已是最新」，等于白发一版。
$liveCode = 0
$liveName = '(未知)'
$liveSources = @()

if ($LiveVersionCode -gt 0) {
    $liveCode = $LiveVersionCode
    $liveSources += "参数指定 $LiveVersionCode"
} else {
    try {
        $live = Invoke-RestMethod -Uri $UpdateUrl -TimeoutSec 20
        $webCode = [int]$live.versionCode
        $liveName = [string]$live.versionName
        if ($webCode -gt $liveCode) { $liveCode = $webCode }
        $liveSources += "update.json $liveName($webCode)"
    } catch {
        Warn "读取 $UpdateUrl 失败（$($_.Exception.Message)）"
    }

    # S3 兜底：只看得到「已经躺在 CDN 上的产物」，比站点数据更严格（只读，DryRun 也会跑）
    if (Get-Command aws -ErrorAction SilentlyContinue) {
        $ls = Invoke-Native -Exe 'aws' -Arguments @('s3', 'ls', "s3://$Bucket/release/")
        if ($ls.ExitCode -eq 0) {
            $s3Max = 0
            foreach ($mm in [regex]::Matches(($ls.Output -join "`n"), 'HamKit_[0-9][^-]*_(\d+)-release\.apk')) {
                $c = [int]$mm.Groups[1].Value
                if ($c -gt $s3Max) { $s3Max = $c }
            }
            if ($s3Max -gt $liveCode) { $liveCode = $s3Max }
            $liveSources += "S3 产物最高 $s3Max"
        }
    }
}

if ($liveSources.Count -gt 0) {
    Info ("线上已知版本：" + ($liveSources -join ' / ') + "  => 按 $liveCode 比较")
} else {
    Warn "无法确定线上版本（网络与 S3 都不可用）。建议用 -LiveVersionCode 明确指定，否则可能发出一个用户收不到的版本。"
}

$codeWasAuto = $false
if ($VersionCode -le 0) {
    if ($currentName -eq $VersionName -and $currentCode -gt $liveCode) {
        # 仓库里已经把这个版本准备好了（版本号已改、只差打包上传），直接沿用
        $VersionCode = $currentCode
        $codeWasAuto = $true
        Info "沿用仓库已准备好的 versionCode = $VersionCode"
    } else {
        $VersionCode = [Math]::Max($currentCode, $liveCode) + 1
        $codeWasAuto = $true
        Info "自动分配 versionCode = $VersionCode"
    }
}
if ($VersionCode -le $liveCode) {
    Die "versionCode $VersionCode 不大于线上 $liveCode：用户端会认为「已是最新」，本次发布不会生效。"
}
if ($VersionCode -le $currentCode -and -not $codeWasAuto) {
    Warn "versionCode $VersionCode 不大于仓库当前 $currentCode（会覆盖同名产物，确认是有意为之）"
}
if ($VersionName -notmatch '^\d+\.\d+\.\d+([-+].+)?$') { Warn "版本名 $VersionName 不是标准 x.y.z 形式" }
$apkName = "HamKit_${VersionName}_${VersionCode}-release.apk"
$tagName = "v$VersionName"
$downloadUrl = "$CdnBase/release/$apkName"
Info "产物名：$apkName"

# ────────────────────────────────────────────────────────────── 更新日志 ──
$changelogText = ''
if (Test-Path $Changelog) {
    $changelogText = Get-Content $Changelog -Raw
    Info "更新日志：$Changelog（$([int]($changelogText.Length)) 字符）"
} else {
    Warn "未找到更新日志文件：$Changelog（发布载荷里 changelog 将为空，官网后台需要手动补）"
}

# ───────────────────────────────────────────── 写入版本号（非 DryRun） ──
if (-not $DryRun) {
    Step "写入版本号到 build.gradle.kts"
    $newText = $gradleText
    $newText = [regex]::Replace($newText, 'managerVersionCode\s+by\s+extra\(\d+\)', "managerVersionCode by extra($VersionCode)")
    $newText = [regex]::Replace($newText, 'managerVersionName\s+by\s+extra\("[^"]*"\)', "managerVersionName by extra(`"$VersionName`")")
    if ($newText -ne $gradleText) {
        Write-Utf8NoBom -Path $RootGradle -Text $newText
        Ok "managerVersionCode=$VersionCode / managerVersionName=$VersionName"
    } else {
        Info '版本号已是目标值，无改动'
    }
} else {
    Step "DryRun：跳过写入 build.gradle.kts"
}

# ───────────────────────────────────────────────────────── 测试与构建 ──
function Invoke-Gradle {
    param([string[]]$Arguments, [string]$LogFile)
    $gradlew = Join-Path $RepoRoot 'gradlew.bat'
    Info ("gradlew " + ($Arguments -join ' '))
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # Gradle 往 stderr 写进度，不要当异常
    & $gradlew @Arguments 2>&1 | Tee-Object -FilePath $LogFile -Append
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return $code
}

if (-not $SkipBuild) {
    if (-not $DryRun) {
        if (-not (Test-Path $BuildDir)) { New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null }
        $log = Join-Path $BuildDir "release-$VersionName-$VersionCode.log"

        # 签名环境变量：app/build.gradle.kts 的 signingConfigs.release 读取这四个
        $env:KEYSTORE_PATH = $KeystorePath
        $env:KEYSTORE_PASSWORD = $storePass
        $env:KEY_ALIAS = $keyAlias
        $env:KEY_PASSWORD = $keyPass

        if (-not $SkipTests) {
            Step "单元测试"
            if ((Invoke-Gradle -Arguments @(':app:testDebugUnitTest') -LogFile $log) -ne 0) { Die "单元测试失败，详见 $log" }
            Ok '单测通过'
        } else {
            Step "单测（已跳过）"
        }

        Step "构建 release APK"
        if ((Invoke-Gradle -Arguments @(':app:assembleRelease') -LogFile $log) -ne 0) { Die "构建失败，详见 $log" }
        Ok "构建完成（日志 $log）"
    } else {
        Step "DryRun：跳过测试与构建"
    }
} else {
    Step "跳过构建（-SkipBuild），使用已有产物"
}

# ─────────────────────────────────────────────────────────── 产物校验 ──
Step "产物校验"
if (-not (Test-Path $OutputMeta)) { Die "找不到 $OutputMeta（先构建一次，或去掉 -SkipBuild）" }
$meta = Get-Content $OutputMeta -Raw | ConvertFrom-Json
$element = $meta.elements | Where-Object { $_.outputFile } | Select-Object -First 1
if (-not $element) { Die 'output-metadata.json 中没有产物记录' }
Info "产物元数据：$($element.outputFile) / $($element.versionName) ($($element.versionCode))"

if ([int]$element.versionCode -ne $VersionCode -or [string]$element.versionName -ne $VersionName) {
    Die @"
产物版本与目标不一致：产物是 $($element.versionName) ($($element.versionCode))，目标是 $VersionName ($VersionCode)。
说明这个 APK 不是本次要发的包 —— 重新构建，或用 -VersionCode/-VersionName 对齐后重跑。
"@
}

$apkPath = Join-Path $OutputDir $apkName
if (-not (Test-Path $apkPath)) {
    # 兜底：产物名可能因 archivesName 变化而不同
    $apkPath = Get-ChildItem $OutputDir -Filter '*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $apkPath) { Die "在 $OutputDir 中找不到任何 APK" }
    Warn "未找到 $apkName，改用最新产物：$(Split-Path -Leaf $apkPath)"
}
$apkFile = Get-Item $apkPath
Info "APK：$($apkFile.FullName)"

$sig = Invoke-Native -Exe $apksigner -Arguments @('verify', '--print-certs', $apkFile.FullName)
if ($sig.ExitCode -ne 0) { Die "apksigner 校验失败：$($sig.Output -join ' ')" }
$certMatch = [regex]::Match(($sig.Output -join "`n"), 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)')
if (-not $certMatch.Success) { Die "无法从 apksigner 输出解析证书指纹" }
$actualCert = ($certMatch.Groups[1].Value -replace ':', '').ToLower()
$actualCert = ($actualCert -replace '(..)(?=.)', '$1:')
Info "实际签名指纹 = $actualCert"
if ($actualCert -ne $ExpectedCertSha256) {
    Die @"
签名不匹配！这个 APK 不是用 $KeystorePath 签的（很可能是 debug key 兜底）。
  期望：$ExpectedCertSha256
  实际：$actualCert
继续上传会让所有老用户无法覆盖安装（须先卸载）。已中止。
"@
}
Ok '签名与 release keystore 一致'

$sha256 = (Get-FileHash -Path $apkFile.FullName -Algorithm SHA256).Hash.ToLower()
$sizeBytes = $apkFile.Length
Info ("SHA-256 = {0}" -f $sha256)
Info ("大小 = {0} 字节 ({1:N1} MB)" -f $sizeBytes, ($sizeBytes / 1MB))

# ──────────────────────────────────────────────────────────────── 上传 ──
$s3Key = "release/$apkName"
$uploaded = $false
if ($DryRun -or $SkipUpload) {
    Step "跳过上传（DryRun / -SkipUpload）"
} else {
    Step "上传到 s3://$Bucket/$s3Key"
    $cp = Invoke-Native -Exe 'aws' -Arguments @(
        's3', 'cp', $apkFile.FullName, "s3://$Bucket/$s3Key",
        '--content-type', 'application/vnd.android.package-archive',
        '--metadata', "sha256=$sha256",
        '--only-show-errors'
    )
    if ($cp.ExitCode -ne 0) { Die "S3 上传失败：$($cp.Output -join ' ')" }
    $headNative = Invoke-Native -Exe 'aws' -Arguments @('s3api', 'head-object', '--bucket', $Bucket, '--key', $s3Key)
    if ($headNative.ExitCode -ne 0) { Die "S3 回读失败：$($headNative.Output -join ' ')" }
    $head = ($headNative.Output -join "`n") | ConvertFrom-Json
    if ([long]$head.ContentLength -ne $sizeBytes) { Die "回读校验失败：S3 上 $($head.ContentLength) 字节，本地 $sizeBytes 字节" }
    Ok "S3 回读一致（$($head.ContentLength) 字节）"
    try {
        $resp = Invoke-WebRequest -Uri $downloadUrl -Method Head -TimeoutSec 30 -UseBasicParsing
        Ok "CDN 可达：$downloadUrl（$($resp.StatusCode)）"
    } catch {
        Warn "CDN HEAD 失败（$($_.Exception.Message)）；新对象首次访问可能需几秒，或等失效后再试"
    }
    $uploaded = $true
}

# ─────────────────────────────────────────────── CloudFront 失效（可选） ──
if ($SkipInvalidate -or $DryRun -or $SkipUpload) {
    Step "跳过 CloudFront 失效"
} else {
    Step "CloudFront 失效（$DistributionId）"
    $distNative = Invoke-Native -Exe 'aws' -Arguments @('cloudfront', 'get-distribution', '--id', $DistributionId, '--query', 'Distribution.DistributionConfig.Aliases.Items')
    $dist = ($distNative.Output -join "`n") | ConvertFrom-Json
    if ($distNative.ExitCode -ne 0 -or -not ($dist -contains 'apk.hamkit.click')) {
        Warn "分发 $DistributionId 的别名是 [$($dist -join ', ')]，不包含 apk.hamkit.click —— 已跳过，避免误刷别的站点"
    } else {
        $invNative = Invoke-Native -Exe 'aws' -Arguments @('cloudfront', 'create-invalidation', '--distribution-id', $DistributionId, '--paths', "/$s3Key")
        if ($invNative.ExitCode -ne 0) {
            Warn "失效提交失败：$($invNative.Output -join ' ')"
        } else {
            $inv = ($invNative.Output -join "`n") | ConvertFrom-Json
            Ok "已提交失效：$($inv.Invalidation.Id)（状态 $($inv.Invalidation.Status)）"
        }
    }
}

# ──────────────────────────────────────────────── 官网发布载荷（关键） ──
Step "生成官网发布载荷"
if (-not (Test-Path $BuildDir)) { New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null }
$payload = [ordered]@{
    versionCode  = $VersionCode
    versionName  = $VersionName
    channel      = 'stable'
    tag          = $tagName
    downloadUrl  = $downloadUrl
    sizeBytes    = $sizeBytes
    sha256       = $sha256
    changelog    = $changelogText
    apkPath      = $apkFile.FullName
    uploadedAt   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
}
$payloadPath = Join-Path $BuildDir "publish-$VersionName.json"
($payload | ConvertTo-Json -Depth 4) | ForEach-Object { Write-Utf8NoBom -Path $payloadPath -Text $_ }
Ok "已写出 $payloadPath"

Write-Host ''
Write-Host '  ── 发布参数（用于 hamkit.click/admin/ 的「版本管理」）──' -ForegroundColor Cyan
Info "versionCode  : $VersionCode"
Info "versionName  : $VersionName"
Info "downloadUrl  : $downloadUrl"
Info "sizeBytes    : $sizeBytes"
Info "sha256       : $sha256"
Info "changelog    : $payloadPath 里的 changelog 字段（粘贴即可）"
Write-Host ''
Write-Host '  ── 收尾清单 ──' -ForegroundColor Cyan
Info "1. 打开 https://hamkit.click/admin/ → 版本管理 → 新增版本，粘贴上面的字段"
Info "2. 校验线上生效：curl $UpdateUrl（versionCode 应为 $VersionCode）"
Info "3. App 端：设置 → 检查更新，应提示 $VersionName"
if (-not $uploaded -and -not $DryRun) { Warn '  （本次未上传，APK 还没到 CDN）' }

# ────────────────────────────────────────────────────── GitHub / git（可选） ──
if ($CreateGitHubRelease -and -not $DryRun) {
    Step "创建 GitHub Release（留档 + App 回退源）"
    $notesPath = Join-Path $BuildDir "notes-$VersionName.md"
    $notes = $changelogText
    if ($notes -notmatch 'Version:\s*\S+\s*\(\d+\)') {
        # App 的 GitHub 回退路径靠这一行提取 versionCode，缺失会取到 0
        $notes = "> Version: $VersionName ($VersionCode)`n`n" + $notes
    }
    Write-Utf8NoBom -Path $notesPath -Text $notes
    $gh = Invoke-Native -Exe 'gh' -Arguments @('release', 'create', $tagName, $apkFile.FullName, '--title', "HamKit $tagName", '--notes-file', $notesPath)
    if ($gh.ExitCode -ne 0) {
        Warn "gh release 失败（可稍后手动补：gh release create $tagName <apk>）：$($gh.Output -join ' ')"
    } else {
        Ok "Release $tagName 已创建"
    }
}

if ($CommitVersion -and -not $DryRun) {
    Step '提交版本号变更'
    $g1 = Invoke-Native -Exe 'git' -Arguments @('add', 'build.gradle.kts')
    $g2 = Invoke-Native -Exe 'git' -Arguments @('commit', '-m', "release: v$VersionName (versionCode $VersionCode)")
    if ($g2.ExitCode -ne 0) { Warn "提交失败，请手动处理：$($g2.Output -join ' ')" } else { Ok '已提交' }
}

if ($Tag -and -not $DryRun) {
    Step "打 tag $tagName"
    $t = Invoke-Native -Exe 'git' -Arguments @('tag', '-a', $tagName, '-m', "HamKit $VersionName (versionCode $VersionCode)")
    if ($t.ExitCode -ne 0) { Warn "打 tag 失败（可能已存在）：$tagName" } else { Ok "已打 tag $tagName（推送：git push origin $tagName）" }
}

Write-Host ''
Ok '完成'
