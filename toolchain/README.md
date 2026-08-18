# Android 构建链

## 固定版本

| 组件 | 版本 |
| --- | --- |
| JDK | 17 |
| Android Gradle Plugin | 9.3.0 |
| Gradle Wrapper | 9.5.0 |
| compileSdk / targetSdk | 36 / 36 |
| minSdk | 29 |
| Build Tools | 36.0.0 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 或 SDK 提供的更高兼容版本 |
| ABI | arm64-v8a |

版本依据：

- <https://developer.android.com/build/releases/agp-9-3-0-release-notes>
- <https://developer.android.com/ndk/downloads/revision_history>
- <https://developer.android.com/studio/projects/install-ndk>

## Linux / CI

必须能够访问 Google Android SDK 与 Maven 官方源，以及 Gradle 发行源。安装 Android Command-line Tools 后，通过 `sdkmanager` 安装：

```text
platform-tools
platforms;android-36
build-tools;36.0.0
ndk;28.2.13676358
cmake;3.22.1
```

将本机 SDK 路径写入未跟踪的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

也可以只在当前终端设置 `ANDROID_SDK_ROOT`。不要把 SDK 路径、下载令牌或签名密钥写入仓库。

## Windows / Android Studio

1. 安装 JDK 17 和当前稳定版 Android Studio。
2. 在 SDK Manager 安装上表中的 Platform、Build Tools、NDK、CMake 与 Platform Tools。
3. 用 Android Studio 打开仓库根目录。
4. 首次同步前运行 `scripts/android-env.sh`（Git Bash/WSL），或人工核对相同组件。
5. 真机打开开发者选项与 USB 调试，用 `adb devices` 确认授权。

## 硬性预检

```bash
./scripts/android-env.sh
```

脚本只读取环境并返回状态，不安装软件，也不修改全局变量。任何必需项缺失都会以非零状态退出；在预检成功前不得声明已生成 APK。

## 当前托管工作区限制（2026-08-18）

本地托管工作区已有 JDK 17、G++ 与 Make，但没有 Android SDK、NDK、Gradle、ADB、CMake 或 Ninja；访问 Android/Gradle 官方下载源的请求被网络策略阻止。仓库因此使用 GitHub Actions 安装固定官方工具链，并以云端编译结果作为 Android 构建依据。
