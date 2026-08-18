# 蛇域进化 Android 原生版

这是与现有网页版本并行开发的 Android 原生工程。它不是 WebView 套壳：大厅使用 Kotlin/Compose，战斗核心使用 C++17，渲染层提供 Vulkan 1.1 与 OpenGL ES 双后端。

## 已锁定目标

- Android 10+（API 29），仅 `arm64-v8a`。
- 竖屏大厅、沉浸式横屏战斗。
- 30/45/60/90/120 FPS 与基于负载、温度的自适应降级。
- Vulkan 自动选择、强制 Vulkan、强制 OpenGL ES。
- 三张地图、三种模式、四个原型、离线 AI、技能升级、模式音乐。
- 三档画质、三档特效、12 款原创皮肤、可编辑按键布局与触感反馈。

## 构建状态

原生工程已经初始化。GitHub Actions 会安装固定版本的 SDK、NDK、CMake 与 Gradle，运行 C++/Kotlin 测试并生成 ARM64 调试 APK。不会提交本机 SDK、NDK、签名密钥或构建产物。

```bash
./scripts/android-env.sh
```

工具链安装与 CI 要求见 `toolchain/README.md`。
