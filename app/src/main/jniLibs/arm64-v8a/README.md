# arm64-v8a mihomo 原生库

本目录需要放置 **arm64-v8a** 架构的 mihomo 原生库。

## 放置要求

文件名必须重命名为：

```
libmihomo.so
```

## 下载来源

从 mihomo Release 页面下载最新版本：

```text
https://github.com/MetaCubeX/mihomo/releases
```

选择文件名形如：

```text
mihomo-android-arm64-v8-vX.XX.X.gz
```

其中 `vX.XX.X` 为版本号。

## 操作示例

```bash
gunzip mihomo-android-arm64-v8-v1.19.27.gz
mv mihomo-android-arm64-v8-v1.19.27 app/src/main/jniLibs/arm64-v8a/libmihomo.so
```

## 验证

```bash
file app/src/main/jniLibs/arm64-v8a/libmihomo.so
```

期望输出包含 `aarch64` 或 `ARM64`。

## 注意

`libmihomo.so` 已加入 `.gitignore`，不会被提交到 Git。这个 README.md 仅用于说明放置要求。
