# 构建与交付状态

## 自动构建

每次推送和拉取请求都会执行两组独立任务：

1. 在 Linux 主机上编译并运行不依赖 Android/GPU 的 C++17 核心测试。
2. 安装固定版本的 Android SDK、NDK、CMake 和 Gradle，执行 Lint、Kotlin 单元测试并构建仅含 `arm64-v8a` 的调试 APK。

APK 仅在工作流完整通过后上传为 GitHub Actions 构建产物。调试 APK 使用 Android 自动生成的测试签名，不用于正式发布。

## 交付门槛

- APK 签名和 ABI 检查通过。
- Android 10+ ARM64 真机安装启动。
- Vulkan 与 OpenGL ES 后端均可进入战斗。
- 120 FPS 选择和自适应回退在一加 Ace 3 上实测。
- 连续运行 20 分钟无崩溃、黑屏、输入失效或游戏速度变化。
