# ChatMini 设计与构建文档

> 本文档用于快速理解本项目的设计方案、技术栈和构建要求，方便在其他环境或对话中继续开发。

---

## 一、项目概述

ChatMini 是一个面向 Android 16（targetSdk = 36，minSdk = 31）的轻量级浏览器 App，核心特点是：

- 内置浏览器引擎（GeckoView）
- 集成 mihomo（Clash Meta）代理核心
- 不注册系统 VPN、不显示 VPN 图标、不影响其他 App
- 通过 SOCKS5 代理让浏览器流量走本 App 内的代理
- App 内可拖动悬浮球，点击展开快捷菜单
- 设置页支持管理快速 URL、导入订阅、切换节点

---

## 二、核心设计决策

### 2.1 为什么用 GeckoView 而不是 WebView？

Android WebView **没有公开的代理配置 API**，无法可靠地将 WebView 流量导向本地代理。

GeckoView（Firefox 的浏览器引擎）支持通过启动参数设置 SOCKS 代理：

```
--proxy-server=socks://127.0.0.1:1080
```

同时通过 GeckoView 配置文件设置 `network.proxy.socks_remote_dns: true`，让 DNS 查询也走 SOCKS5 代理。

因此选择 GeckoView 作为浏览器引擎。

### 2.2 为什么用 SOCKS5 而不是 HTTP 代理？

GeckoView 支持 SOCKS 代理启动参数，但**不支持 HTTP/HTTPS 代理配置**。所以 mihomo 必须暴露一个本地 SOCKS5 端口（默认 1080），GeckoView 通过 SOCKS5 连接它。

代码里启动参数写的是 `socks://`（GeckoView 同样识别为 SOCKS5），并配合 prefs 配置 `network.proxy.type`、`network.proxy.socks`、`network.proxy.socks_port`、`network.proxy.socks_remote_dns`。

### 2.3 为什么不使用 VpnService？

VpnService 会在系统状态栏显示 VPN 图标，并在系统设置中显示 VPN 入口。本项目的需求是代理只在本 App 内生效、不影响系统，因此不使用 VpnService，而是：

- 在 App 内启动一个普通 Android Service
- Service 中运行 mihomo 原生库（`libmihomo.so`）
- mihomo 监听本地 SOCKS5 端口
- GeckoView 配置代理指向该端口

### 2.4 mihomo 二进制文件

mihomo 核心以**原生库**形式集成，放在 `app/src/main/jniLibs/<abi>/libmihomo.so`。

原因：

- Android 10+ 上，`nativeLibraryDir` 中的原生库允许直接执行
- 不需要向系统注册 VPN
- 通过 ProcessBuilder 在 App 私有目录中运行，加载生成的 `config.yaml`

运行时，App 直接执行 `nativeLibraryDir/libmihomo.so`，工作目录为 `files/mihomo`。

---

## 三、项目架构

```
ChatMini/
├── app/src/main/kotlin/com/chatmini/app/
│   ├── ChatMiniApplication.kt          # Application 入口
│   ├── MainActivity.kt                 # 主界面：GeckoView + 悬浮球
│   ├── SettingsActivity.kt             # 设置界面
│   ├── data/
│   │   ├── AppSettings.kt              # 设置数据类
│   │   ├── ClashConfigGenerator.kt     # 生成 mihomo YAML 配置
│   │   ├── ProxyConfig.kt              # 代理相关常量
│   │   ├── SettingsRepository.kt       # SharedPreferences 数据持久化
│   │   ├── SubscriptionParser.kt       # Base64 订阅解析
│   │   └── UrlItem.kt                  # URL 数据类
│   ├── service/
│   │   └── ProxyService.kt             # 前台服务：运行 mihomo
│   └── ui/theme/                       # Compose Material3 主题
├── app/src/main/jniLibs/<abi>/libmihomo.so   # mihomo 原生库（需自行准备）
├── app/src/main/res/                   # 资源文件
├── app/build.gradle.kts
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── local.properties                    # Android SDK 路径（本地生成）
```

### 数据流

```
┌─────────────────────────────────────────┐
│           MainActivity                  │
│  ┌─────────────────────────────────┐   │
│  │         GeckoView               │   │
│  │  --proxy-server=socks://...     │   │
│  └─────────────────────────────────┘   │
│           可拖动悬浮球                   │
└──────────────────┬──────────────────────┘
                   │
        ┌──────────┴──────────┐
        │    ProxyService     │
        │  （前台服务）        │
        │  运行 mihomo 原生库  │
        │  监听 127.0.0.1:1080 │
        │  控制 API :9090      │
        └──────────┬──────────┘
                   │
        ┌──────────┴──────────┐
        │   SettingsActivity  │
        │  - URL 管理          │
        │  - 订阅导入          │
        │  - 节点切换          │
        └─────────────────────┘
```

---

## 四、关键模块说明

### 4.1 MainActivity

- 使用 Compose 构建界面
- 通过 `AndroidView` 嵌入 GeckoView
- 创建 GeckoRuntime 时传入 SOCKS 代理参数及 prefs
- 右下角悬浮球：长按拖动、点击展开菜单
- 菜单功能：快速 URL 列表、刷新、设置、退出
- 监听设置变化，自动加载选中的 URL
- 页面加载成功后，通过 DuckDuckGo 图标服务下载网站 favicon

### 4.2 ProxyService

- 普通前台 Service（非 VpnService）
- 从 `nativeLibraryDir` 执行 `libmihomo.so`，加载 `config.yaml`
- 通过 `127.0.0.1:9090` 的 REST API 切换节点
- 关闭 App 时停止服务并释放资源
- 启动前会清理残留 mihomo 进程并检测端口占用

### 4.3 SettingsActivity

- 代理手动启停按钮
- 「打开 App 自动启动代理」开关
- 快速 URL 的增删改查和选择
- 订阅 URL 导入（Base64 解码）
- 节点列表展示和切换

### 4.4 SubscriptionParser

支持解析以下节点类型的 Base64 订阅：

- SS (Shadowsocks)
- VMess
- Trojan
- VLESS
- AnyTLS

解析后生成 mihomo 可用的 YAML 配置文件。

### 4.5 ClashConfigGenerator

将解析后的节点列表转换为 mihomo YAML 配置：

- `socks-port: 1080`
- `port: 0`（禁用 HTTP 代理）
- `external-controller: 127.0.0.1:9090`
- 默认规则：国内直连，其他走代理组

---

## 五、构建环境要求

### 5.1 必需软件

| 软件 | 版本要求 | 说明 |
|------|----------|------|
| JDK | 17 ~ 21 | **不要使用 JDK 25**，Gradle 8.9 的 Kotlin 编译器无法识别 JDK 25 |
| Android SDK | API 36 | 已安装 `platforms/android-36` 和 `build-tools/36.0.0` |
| Gradle | 8.9 | 项目已配置 wrapper，也可使用本地安装 |
| mihomo 原生库 | arm64-v8a / x86_64 | 需自行放入 `app/src/main/jniLibs/<abi>/libmihomo.so` |

### 5.2 环境变量

```bash
export JAVA_HOME=/e/ProgramData/jdk-21
export ANDROID_HOME=/e/ProgramData/android_sdk
export ANDROID_SDK_ROOT=/e/ProgramData/android_sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

### 5.3 当前环境配置（参考）

本项目的实际环境：

- JDK: `E:\ProgramData\jdk-21`
- Android SDK: `E:\ProgramData\android_sdk`
- Gradle: `E:\ProgramData\gradle-8.9`（也可使用 wrapper）
- 项目路径: `E:\workspace\chatmini`

---

## 六、构建步骤

### 6.1 准备 mihomo 原生库

从 [mihomo Releases](https://github.com/MetaCubeX/mihomo/releases) 下载对应架构：

| 目标 ABI | Release 文件名 | 用途 |
|---------|---------------|------|
| arm64-v8a | `mihomo-android-arm64-v8-vX.XX.X.gz` | 真机 ARM64 设备 |
| x86_64 | `mihomo-android-amd64-vX.XX.X.gz` | 纯 x86_64 模拟器/设备 |

解压后重命名为 `libmihomo.so`，放入对应 ABI 目录：

```
app/src/main/jniLibs/arm64-v8a/libmihomo.so
app/src/main/jniLibs/x86_64/libmihomo.so
```

> `vX.XX.X` 为具体版本号。不要下载带 `cgo`、`compatible`、`go120`、`go122` 等后缀的版本，除非你知道它们的区别。

### 6.2 配置本地环境

```bash
cd E:\workspace\chatmini
set JAVA_HOME=E:\ProgramData\jdk-21
set ANDROID_HOME=E:\ProgramData\android_sdk
set ANDROID_SDK_ROOT=E:\ProgramData\android_sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%PATH%
```

或创建 `local.properties`：

```properties
sdk.dir=E\:\\ProgramData\\android_sdk
```

### 6.3 执行构建

```bash
# 使用 Gradle Wrapper
./gradlew assembleDebug

# 或使用本地 Gradle
gradle assembleDebug --no-daemon
```

构建产物位于：

```
app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
app/build/outputs/apk/pcSimu/debug/app-pcSimu-debug.apk
app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
```

| Flavor | 原生库 ABI | 适用场景 |
|--------|-----------|---------|
| arm64 | arm64-v8a | 真机 ARM64 设备 |
| pcSimu | arm64-v8a | 支持 ARM 转译的 PC 模拟器 |
| x86_64 | x86_64 | 纯 x86_64 模拟器/设备 |

### 6.4 suppressUnsupportedCompileSdk 说明

由于当前 Android Gradle Plugin 8.7.0 未官方测试 compileSdk 36，构建时会提示警告。已在 `gradle.properties` 中添加：

```properties
android.suppressUnsupportedCompileSdk=36
```

---

## 七、运行时说明

### 7.1 首次使用

1. 打开 App
2. 点击悬浮球 → 设置
3. 在「订阅导入」处粘贴订阅链接
4. 点击「导入订阅」
5. 导入成功后，在「节点列表」选择一个节点
6. 返回主页，浏览器会通过 SOCKS5 代理加载网页

### 7.2 悬浮球操作

- **长按并拖动**：移动悬浮球位置
- **点击**：展开菜单（快速 URL 列表 / 刷新 / 设置 / 退出）

### 7.3 代理生命周期

- 若开启「打开 App 自动启动代理」，每次启动 App 自动运行代理
- 关闭 App 时，`MainActivity.onDestroy()` 在 `isFinishing` 为 true 时调用 `ProxyService.stop()`
- mihomo 进程会被终止，通知消失

### 7.4 安装到手机

- Debug APK 可直接安装
- 需要在手机设置中开启「允许安装未知来源应用」或打开「USB 调试」用 adb 安装
- 不需要额外证书

```bash
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

---

## 八、网络与依赖仓库

为加速依赖下载，已在 `settings.gradle.kts` 中配置阿里云镜像：

```kotlin
maven("https://maven.aliyun.com/repository/public")
maven("https://maven.aliyun.com/repository/google")
maven("https://maven.aliyun.com/repository/gradle-plugin")
```

GeckoView 仍然需要从官方 Maven 仓库下载：

```kotlin
maven("https://maven.mozilla.org/maven2/")
```

---

## 九、已知限制与注意事项

1. **JDK 版本**：必须使用 JDK 17 ~ 21，JDK 25 不兼容当前 Gradle 8.9 的 Kotlin DSL 编译器。
2. **GeckoView 包体**：集成 GeckoView 后 APK 体积显著增大（约 50~80MB）。
3. **mihomo 架构**：`app/src/main/jniLibs/<abi>/libmihomo.so` 必须与目标设备 CPU 架构匹配，否则代理无法启动。
4. **订阅格式**：当前支持 SS / VMess / Trojan / VLESS / AnyTLS。其他协议需扩展 `SubscriptionParser.kt`。
5. **GeckoView 代理限制**：仅支持 SOCKS5，不支持 HTTP/HTTPS 代理。
6. **编译警告**：AGP 8.7.0 对 compileSdk 36 会提示未测试警告，已通过 `gradle.properties` 抑制。

---

## 十、后续可扩展方向

- 在 App 内自动下载对应 ABI 的 mihomo 二进制
- 支持更多代理协议（Hysteria2、Tuic 等）
- 添加地址栏、前进/后退、多标签页
- 节点延迟测试和自动选择
- Release 签名配置
- 适配 Android 16 新特性（如更严格的 edge-to-edge、预测返回手势等）
