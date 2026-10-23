# AndroidTerminal

[简体中文](README.md) | [English](README.en.md)

**AndroidTerminal** 是运行于 Android 设备上的终端应用，同时包含可复用的 Android 执行引擎（AT Engine）。它提供本地 Shell 执行和通过 ADB 无线调试（TLS 1.3）的远程命令执行能力。

## 下载安装

| 用户类型 | 入口 | 状态 |
|----------|------|------|
| 普通用户 | APK | 发布准备中 |
| 开发者 | [SDK v0.4.0](https://github.com/WingMoval/AndroidTerminal/releases/tag/v0.4.0) | 已发布 |

## 快速开始

1. 安装 AndroidTerminal App
2. 在设备上手动开启**无线调试**（开发者选项 → 无线调试）
3. 打开 AndroidTerminal，点击**配对**，输入设备上显示的配对码
4. 配对成功后，应用自动建立 ADB 连接
5. 在终端中输入命令并执行

> **首次使用需要手动配对。** 配对完成后，应用会自授予 `WRITE_SECURE_SETTINGS` 权限，后续会话自动开启无线调试（Auto-WD），无需重复配对。

## 核心功能

| 功能 | 说明 | 状态 |
|------|------|------|
| Local Shell | 设备本地 Shell 执行（无需 ADB） | 已验证 |
| Wireless ADB | ADB 无线调试连接（TLS 1.3） | 已验证 |
| SPAKE2 配对 | AOSP 兼容的无线调试配对 | 已验证 |
| mDNS 发现 | NsdManager 自动发现 ADB 服务 | 已验证 |
| 持久会话 | 交互式终端（输入/输出/Resize/Ctrl-C） | 已验证 |
| Auto-WD | 首次配对后自动开启无线调试 | 已验证 |
| 连接恢复 | 断线后自动重连（有界，无重放） | 已验证 |
| 身份管理 | RSA-2048 密钥对，首次使用自动生成 | 已验证 |

## 系统架构

```text
Consumer / Product (Android App, CLI, AG)
        │
        ▼  AT Engine Public Boundary (:at-engine-api)
        ▼
        AT Engine (:at-engine)
   ├─ One-shot Execution
   └─ Persistent Terminal
        │
        ▼  Backend Adapter
   ├─ Local Backend (:at-backend-local)
   └─ ADB Backend (:at-backend-adb)
        │
        ▼  Execution Provider
   ├─ Local Process / PTY
   └─ ADB Engine (:adb-engine) → adbd (TLS 1.3, shell v2)
```

| 模块 | 职责 |
|------|------|
| `:at-engine-api` | 公共消费者契约（仅接口） |
| `:at-engine` | AT Engine 实现（one-shot + persistent） |
| `:at-engine-spi` | Backend SPI（内部） |
| `:at-backend-local` | 本地 Shell 执行（无 ADB） |
| `:at-backend-adb` | ADB Backend 适配器 |
| `:adb-engine` | ADB 引擎（配对、TLS、连接、shell v2） |
| `:at-composition-android` | Android 组合根（装配） |
| `:at-cli` | CLI 前端（可选） |
| `:app` | Android 应用（产品宿主） |

## 开发者集成

### 源码集成

```kotlin
implementation(project(":at-engine-api"))
implementation(project(":at-composition-android"))
// 可选：
implementation(project(":at-cli"))
```

### 预编译 AAR/JAR 集成

将 Release 中的 AAR/JAR 文件放入项目 `libs/` 目录：

```kotlin
implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
```

内部模块（`at-engine`、`at-engine-spi`、`at-backend-local`、`at-backend-adb`、`adb-engine`）通过 `at-composition-android` 传递依赖，**不要直接依赖**（CONSUMER_BOUNDARY_VIOLATION）。

完整集成指南：[Consumer Handoff](docs/handoff/AT_V4_CONSUMER_HANDOFF.md)

## 编译构建

前置条件：
- Android SDK (platform-34+)
- NDK (r26+)
- CMake 3.22.1
- JDK 21

```powershell
.\gradlew.bat assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`

ABI：`arm64-v8a`（BoringSSL 静态库仅支持 arm64）

## 已知限制

- 仅支持 arm64-v8a（不支持 x86、armeabi-v7a）
- 单设备连接（不支持多设备）
- ADB 线程上使用阻塞 I/O（生产环境不在主线程）
- 首次使用必须手动配对（非零接触）

## 开源许可证

| 组件 | 许可证 |
|------|--------|
| AT 原始代码 | Apache-2.0（[`LICENSE`](LICENSE)） |
| BoringSSL (pre-2025-02) | OpenSSL BSD + SSLeay BSD + ISC + MIT（[历史许可证](docs/legal/boringssl/HISTORICAL_BORINGSSL_LICENSE.md)） |

- 项目声明：[`NOTICE`](NOTICE)
- 第三方声明：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- BoringSSL 合规文档：[`docs/legal/boringssl/`](docs/legal/boringssl/)

## 版本

```text
当前公开版本: v0.4.0
稳定消费者基线: AT.V4-CONSUMER-FREEZE-V1 (7216d0e)
```
