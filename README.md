# 蛇域进化 Android 原生版

这是与现有网页版本并行开发的 Android 原生工程。它不是 WebView 套壳：大厅与触控 HUD 使用 Kotlin 原生 View，确定性战斗核心使用 C++17，场景层提供真实 Vulkan 与 OpenGL ES 3 双后端。

`0.1.2` 增加 Android 16 专用兼容路径：默认战斗完全使用 Kotlin 模拟与 Canvas，不加载 C++、Vulkan 或 OpenGL 驱动；进阶接口仍可在设置中手动启用。应用同时记录战场启动阶段，异常时保留可复制的设备诊断信息。

## 已锁定目标

- Android 10+（API 29），仅 `arm64-v8a`。
- 竖屏大厅、沉浸式横屏战斗。
- 30/45/60/90/120 FPS、Surface 级高刷新率请求，以及基于实际帧率、温度和省电状态的两级特效降载。
- Android 16 兼容模式、稳定自动模式、手动 Vulkan、手动 OpenGL ES；兼容模式不会触发任何原生库加载。
- 三张带独立动态场景的地图、三种模式、四个原型、离线 AI、减速后的技能升级、三种有实际战斗效果的主动技能、模式音乐。
- 三档画质、三档特效、12 款原创皮肤、可编辑按键布局与触感反馈。

## 已实现的渲染路径

- `Compat`：纯 Kotlin + Canvas，是新安装与异常迁移后的默认路径。
- `Auto`：优先稳定 OpenGL ES；初始化失败时自动回退 Canvas。
- `Vulkan`：Android Surface、Swapchain、预旋转、单 RenderPass、动态图形管线、双帧持久映射顶点缓冲、Mailbox/FIFO 呈现。
- `OpenGL ES`：ES 3.0 着色器与单批次三角形场景绘制。
- 最后保留 Canvas 兼容路径，确保 GPU 初始化异常时仍可退出战场而不是黑屏。

## 构建与验证

GitHub Actions 会安装固定版本的 SDK、NDK、CMake 与 Gradle，运行数据校验、9 项 C++ 测试、Android lint/Kotlin 测试，并使用 NDK `glslc` 生成 Vulkan SPIR-V。流水线会执行官方 `zipalign -P 16` 检查，并在 API 36 x86_64 模拟器中进入兼容战场、保持运行 5 秒后才通过。最终 ARM64 APK 仍会检查地图资源、JNI 符号与 Vulkan 依赖。不会提交本机 SDK、NDK、签名密钥或构建产物。

```bash
./scripts/android-env.sh
```

工具链安装与 CI 要求见 `toolchain/README.md`。
