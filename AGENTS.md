# ChatMini - Agent Guide

## 项目概述

ChatMini 是一个面向 Android 16（targetSdk = 36，minSdk = 31）的内置浏览器 App，集成 mihomo（Clash Meta）代理核心。核心特点是：不注册系统 VPN、不显示 VPN 图标，只让 App 内的浏览器通过本地 SOCKS5 代理访问网络。

## 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose + Material3
- **浏览器引擎**：GeckoView（Mozilla Firefox 引擎）
- **代理核心**：mihomo 二进制文件
- **构建工具**：Gradle 8.9 + Android Gradle Plugin 8.7.0
- **最低 JDK**：JDK 17，**推荐 JDK 21**，**不要使用 JDK 25**
- **网络库**：OkHttp

## 关键设计决策

1. **为什么用 GeckoView 不用 WebView？**
   - Android WebView 没有公开代理配置 API。
   - GeckoView 支持通过启动参数设置 SOCKS 代理：`--proxy-server=socks://127.0.0.1:1080`。
   - 同时通过 GeckoView 配置文件设置 `network.proxy.socks_remote_dns: true`，让 DNS 查询也走 SOCKS5 代理，避免本地 DNS 污染或解析失败。

2. **为什么用 SOCKS5 不用 HTTP 代理？**
   - GeckoView 不支持 HTTP/HTTPS 代理配置，只支持 SOCKS。
   - mihomo 监听本地 SOCKS5 端口（默认 1080）。
   - 代码中启动参数写的是 `socks://`（GeckoView 同样识别为 SOCKS5），同时通过 prefs 设置 `network.proxy.socks_remote_dns: true`。

3. **为什么不使用 VpnService？**
   - VpnService 会显示系统 VPN 图标并在系统设置中注册 VPN。
   - 本项目通过普通前台 Service 运行 mihomo，仅影响本 App 内的浏览器。

4. **mihomo 如何集成？**
   - 以原生库形式按 ABI 放在 `app/src/main/jniLibs/<abi>/libmihomo.so`（当前支持 `arm64-v8a` 和 `x86_64`）。
   - Android 安装时会自动提取到 `nativeLibraryDir`，该目录在 Android 10+ 上允许执行原生二进制。
   - 通过 ProcessBuilder 直接执行 `nativeLibraryDir/libmihomo.so`，加载生成的 `config.yaml`。
   - 工作目录仍为 `files/mihomo`，用于存放 `config.yaml` 及运行时文件。

## 项目结构

```
app/src/main/kotlin/com/chatmini/app/
├── ChatMiniApplication.kt       # Application 入口
├── MainActivity.kt              # 主界面：GeckoView + 可展开 URL 列表的悬浮球
├── SettingsActivity.kt          # 设置界面
├── data/
│   ├── AppSettings.kt           # 设置数据类
│   ├── ClashConfigGenerator.kt  # 生成 mihomo YAML
│   ├── ProxyConfig.kt           # 代理常量
│   ├── SettingsRepository.kt    # SharedPreferences 存储
│   ├── SubscriptionParser.kt    # Base64 订阅解析
│   └── UrlItem.kt               # URL 数据类
├── service/
│   └── ProxyService.kt          # 运行 mihomo 的前台服务
└── ui/theme/                    # Compose 主题
```

## 构建要求

### 环境变量

```bash
export JAVA_HOME=/e/ProgramData/jdk-21
export ANDROID_HOME=/e/ProgramData/android_sdk
export ANDROID_SDK_ROOT=/e/ProgramData/android_sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
```

### 构建命令

```bash
cd E:/workspace/chatmini
./gradlew assembleDebug --no-daemon
```

产物路径：

```
app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
app/build/outputs/apk/pcSimu/debug/app-pcSimu-debug.apk
app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
```

## 重要约束

- **JDK 版本**：必须使用 JDK 17~21。JDK 25 会导致 Gradle Kotlin DSL 编译失败。
- **mihomo 二进制**：必须自行下载并放入对应的 ABI 目录（`app/src/main/jniLibs/arm64-v8a/libmihomo.so` 或 `app/src/main/jniLibs/x86_64/libmihomo.so`），需与目标设备 CPU 架构匹配。
- **依赖仓库**：`settings.gradle.kts` 已配置阿里云镜像加速，但 GeckoView 仍需从 `https://maven.mozilla.org/maven2/` 下载。
- **AGP 警告**：AGP 8.7.0 未官方测试 compileSdk 36，已在 `gradle.properties` 中通过 `android.suppressUnsupportedCompileSdk=36` 抑制。
- **代理协议**：仅支持 SOCKS5，不支持 HTTP 代理。

## 支持的订阅节点类型

`SubscriptionParser.kt` 当前支持：

- SS (Shadowsocks)
- VMess
- Trojan
- VLESS
- AnyTLS

如需支持更多协议，扩展 `SubscriptionParser.kt` 并在 `ClashConfigGenerator.kt` 中生成对应配置。

## 修改代码时的注意事项

1. **最小修改原则**：只改必要的文件，不要重构无关逻辑。
2. **保持现有架构**：新增功能优先放在对应的 `data/`、`service/` 或 `ui/` 包下。
3. **Compose 风格**：UI 继续使用 Jetpack Compose + Material3，保持与现有代码一致。
4. **权限**：新增权限必须在 `AndroidManifest.xml` 中声明，运行时权限在 `MainActivity.kt` 中申请。
5. **代理配置**：修改 `ProxyConfig.kt` 中的端口后，必须同步修改 `ClashConfigGenerator.kt` 以及 `MainActivity.kt` 中 `writeGeckoConfigFile()` 写入的 GeckoView 配置文件和启动参数。
6. **进程生命周期与残留清理**：`ProxyService` 启动 mihomo 前会根据 `files/mihomo/mihomo.pid` 清理上一次异常退出残留的 mihomo 进程；`AndroidManifest.xml` 中为 `ProxyService` 设置了 `android:stopWithTask="true"`，并在 `onTaskRemoved()` 中主动停止代理。修改相关生命周期或端口逻辑时，请勿破坏此清理逻辑。
7. **悬浮按钮与 URL 列表**：点击悬浮球后展开用户在设置页添加的 URL 列表，每个 URL 按钮默认显示域名首字母；网站加载成功后会自动从 DuckDuckGo 图标服务（`https://icons.duckduckgo.com/ip3/<host>.ico`）下载图标并替换默认图标。`UrlItem.iconPath` 保存在 `SharedPreferences` 中，图标文件保存在 `files/favicons/`。修改相关逻辑时请保持 `SettingsRepository` 与 UI 的同步。

## PC 模拟器调试

PC 端模拟器（MuMu、BlueStacks、LDPlayer 等）多为 **x86_64**。由于 mihomo 原生二进制对 ARM 转译的兼容性不稳定，建议直接构建并安装 `app-x86_64-debug.apk`，同时放入 x86_64 架构的 mihomo 二进制。

> **注意**：部分模拟器（如 MuMu）即使安装 x86_64 APK，仍可能强制启用 ARM 转译并导致 GeckoView 或 mihomo 崩溃。若遇到段错误/SIGILL，请在真实 ARM64 设备上测试。

### MuMu 连接

```bash
adb connect 127.0.0.1:5555
adb connect 127.0.0.1:16384
adb devices
```

### 检查是否支持 ARM 转译

```bash
adb -s 127.0.0.1:5555 shell getprop ro.product.cpu.abilist
```

若输出包含 `arm64-v8a`，即可直接安装 arm64 包。

### 安装到模拟器

对于支持 ARM 转译的模拟器，可安装 arm64 包：

```bash
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
```

对于纯 x86_64 模拟器，建议安装 x86_64 包：

```bash
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
```

### 原生 x86_64 包

`x86_64` flavor 已存在，构建时会打包 x86_64 架构的 GeckoView 依赖和 `libmihomo.so`。

## 常用调试命令

```bash
# 清理构建
./gradlew clean --no-daemon

# 安装到手机
adb install app/build/outputs/apk/arm64/debug/app-arm64-debug.apk

# 安装到 x86_64 模拟器
adb install app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk

# 查看日志
adb logcat -s ProxyService:D MainActivity:D *:S
```
