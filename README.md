# ChatMini

一个面向 Android 16 的内置浏览器 App，集成 mihomo（Clash Meta）代理核心，支持订阅导入、节点切换、App 内悬浮球快捷操作。

## 主要功能

- 使用 GeckoView 作为内置浏览器引擎
- 通过 SOCKS5 代理将浏览器流量交给本 App 内的 mihomo 核心处理
- 不注册系统 VPN，不显示系统 VPN 图标，不影响其他 App
- App 内可拖动悬浮球，点击展开菜单
- 设置页管理快速 URL、导入订阅、切换节点
- 关闭 App 时自动停止代理服务

## 项目结构

```
app/src/main/kotlin/com/chatmini/app/
├── ChatMiniApplication.kt      # Application 入口
├── MainActivity.kt              # 主浏览页 + 悬浮球
├── SettingsActivity.kt          # 设置页
├── data/
│   ├── AppSettings.kt           # 设置数据类
│   ├── ClashConfigGenerator.kt  # 生成 mihomo YAML 配置
│   ├── ProxyConfig.kt           # 代理常量
│   ├── SettingsRepository.kt    # 本地设置存储
│   ├── SubscriptionParser.kt    # Base64 订阅解析
│   └── UrlItem.kt               # URL 数据类
├── service/
│   └── ProxyService.kt          # 运行 mihomo 后台服务
└── ui/theme/                    # Compose 主题
```

## 前置要求

1. Android Studio Ladybug (2024.2.1) 或更高版本
2. JDK 17 ~ 21（**不要使用 JDK 25**，Gradle 8.9 的 Kotlin 编译器无法识别 JDK 25）
3. Android SDK 36（Android 16）
4. 有效网络连接（首次构建需要下载依赖）

## 构建步骤

### 1. 准备 mihomo 原生库

本 App 通过执行 mihomo 原生库来提供代理服务。构建前需要手动下载对应 ABI 的 mihomo 二进制，重命名为 `libmihomo.so`，放入 `app/src/main/jniLibs/<abi>/`。

#### 1.1 下载

访问 mihomo Release 页面，选择最新版本：

```
https://github.com/MetaCubeX/mihomo/releases
```

在 Assets 中找到 Android 相关文件。常见文件名如下（`vX.XX.X` 为版本号）：

| 目标 ABI | Release 文件名 | 用途 |
|---------|---------------|------|
| arm64-v8a | `mihomo-android-arm64-v8-vX.XX.X.gz` | 真机 ARM64 设备 |
| x86_64 | `mihomo-android-amd64-vX.XX.X.gz` | 纯 x86_64 模拟器/设备 |
| armeabi-v7a | `mihomo-android-armv7-vX.XX.X.gz` | 旧 32 位 ARM 设备（本项目未提供对应 flavor） |

> 不要下载带 `cgo`、`compatible`、`go120`、`go122` 等后缀的版本，除非明确知道其区别。普通 `mihomo-android-arm64-v8` / `mihomo-android-amd64` 即可。

#### 1.2 解压与放置

以 arm64 为例：

```bash
# Linux / macOS / Git Bash
gunzip mihomo-android-arm64-v8-v1.19.27.gz
mkdir -p app/src/main/jniLibs/arm64-v8a
mv mihomo-android-arm64-v8-v1.19.27 app/src/main/jniLibs/arm64-v8a/libmihomo.so

# x86_64 同理
gunzip mihomo-android-amd64-v1.19.27.gz
mkdir -p app/src/main/jniLibs/x86_64
mv mihomo-android-amd64-v1.19.27 app/src/main/jniLibs/x86_64/libmihomo.so
```

最终目录结构应为：

```
app/src/main/jniLibs/
├── arm64-v8a/
│   └── libmihomo.so
└── x86_64/
    └── libmihomo.so
```

#### 1.3 验证架构

可以用 `file` 命令确认：

```bash
file app/src/main/jniLibs/arm64-v8a/libmihomo.so
# 期望输出包含：aarch64 或 ARM64

file app/src/main/jniLibs/x86_64/libmihomo.so
# 期望输出包含：x86-64 或 amd64
```

### 2. 选择 Build Flavor

项目提供三个 flavor：

| Flavor | 打包的原生库 ABI | mihomo 来源 | 适用场景 |
|--------|----------------|------------|---------|
| `arm64` | arm64-v8a | `jniLibs/arm64-v8a/libmihomo.so` | 真机 ARM64 设备（推荐） |
| `pcSimu` | arm64-v8a | `jniLibs/arm64-v8a/libmihomo.so` | 支持 ARM 转译的 PC 模拟器 |
| `x86_64` | x86_64 | `jniLibs/x86_64/libmihomo.so` | 纯 x86_64 模拟器/设备 |

> `pcSimu` 与 `arm64` 打包的 ABI 相同，区别在于命名。PC 模拟器若支持 ARM 转译可任选其一；若遇到崩溃，优先尝试 `x86_64`。

### 3. 同步并构建

在 Android Studio 中点击 **Sync Project with Gradle Files**，选择对应 flavor 后构建；或在终端执行：

```bash
# 构建所有 debug 变体
./gradlew assembleDebug --no-daemon

# 只构建指定 flavor
./gradlew assembleArm64Debug --no-daemon
./gradlew assembleX86_64Debug --no-daemon
```

构建产物路径：

```
app/build/outputs/apk/arm64/debug/app-arm64-debug.apk
app/build/outputs/apk/pcSimu/debug/app-pcSimu-debug.apk
app/build/outputs/apk/x86_64/debug/app-x86_64-debug.apk
```

## 使用说明

### 首次使用

1. 打开 App，进入设置页
2. 在「订阅导入」处粘贴订阅链接，点击导入
3. 导入成功后，在「节点列表」选择一个节点
4. 返回主页，浏览器会通过 SOCKS5 代理加载网页

### 悬浮球操作

- **长按并拖动**：移动悬浮球位置
- **点击**：展开菜单（快速 URL 列表 / 刷新 / 设置 / 退出）

### 设置项

- **打开 App 自动启动代理**：开启后每次启动 App 自动运行代理服务
- **快速 URL**：维护常用网址，选择后返回主页即加载
- **订阅导入**：从 URL 下载并解析 Base64 订阅
- **节点列表**：切换当前使用的代理节点

## 重要说明

### 为什么使用 SOCKS5 而不是 HTTP 代理？

GeckoView 目前**不支持通过 API 配置 HTTP/HTTPS 代理**，但支持通过启动参数设置 SOCKS 代理。因此本 App 让 mihomo 监听本地 SOCKS5 端口（默认 1080），并将 GeckoView 的启动参数设为：

```
--proxy-server=socks://127.0.0.1:1080
```

同时通过 GeckoView 配置文件设置 `network.proxy.socks_remote_dns: true`，让 DNS 查询也走 SOCKS5 代理。

### 为什么不使用 VpnService？

使用 VpnService 会在系统状态栏显示 VPN 图标，并且会在系统设置中显示 VPN 入口。由于项目需求是希望代理只在本 App 内生效、不影响系统，因此选择 GeckoView + 本地 SOCKS 代理的方案。

### 订阅链接 5 分钟失效

订阅导入会在 App 内直接下载链接内容并生成本地配置文件，之后切换节点不再需要访问订阅链接。因此即使订阅链接失效，已导入的节点仍可正常使用，直到下次重新导入。

### 支持的节点类型

当前解析器支持：

- SS (Shadowsocks)
- VMess
- Trojan
- VLESS
- AnyTLS

如果订阅包含其他类型（如 Hysteria2、Tuic 等），可能需要扩展 `SubscriptionParser.kt` 中的解析逻辑。

## 常见问题排查

### 代理状态显示“代理未就绪”

1. 检查是否已导入订阅并选择了节点。
2. 查看 `logcat` 中 `ProxyService` 的日志，确认 mihomo 是否成功启动：
   ```bash
   adb logcat -s ProxyService:D *:S
   ```
3. 常见原因：
   - `mihomo binary not found in nativeLibraryDir`：对应 ABI 的 `libmihomo.so` 缺失或放错目录。
   - `config not found, please import subscription first`：尚未导入订阅。
   - `proxy ports still occupied`：有其他代理 App 占用了 1080/9090 端口。

### 模拟器上崩溃 / SIGILL

PC 模拟器对 ARM 转译的兼容性不稳定。若安装 `arm64` 包后崩溃，尝试：

1. 使用 `x86_64` flavor 构建并安装 `app-x86_64-debug.apk`。
2. 确保 `app/src/main/jniLibs/x86_64/libmihomo.so` 是 x86_64 架构。

### 网页无法打开但代理已运行

1. 检查订阅是否包含支持的节点类型。
2. 在设置页切换一个不同节点再试。
3. 检查目标网站是否被当前规则放行（默认规则为 `MATCH,Proxy`，一般全部走代理）。

## 已知限制

1. **包体较大**：由于集成了 GeckoView 和 mihomo，Release APK 大约 50~80MB。
2. **mihomo 二进制需手动准备**：未随源码提供，需从 Release 下载并放入 `jniLibs/<abi>/`。
3. **GeckoView 代理限制**：仅支持 SOCKS5，不支持 HTTP/HTTPS 代理。

## 后续可优化方向

- 在 App 内自动下载对应 ABI 的 mihomo 二进制
- 支持更多代理协议
- 添加地址栏和前进/后退按钮
- 支持多标签页
- 添加节点延迟测试
