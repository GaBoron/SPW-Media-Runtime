# SDK 接入示例

适用于 Runtime/SDK 0.1.0、SPW Workshop API dev23 与 JDK 25。安装并启用同版本 Runtime 后，运行 `./gradlew.bat examplePlugin`，将 `build/distributions/spw-media-example-0.1.0.spmod` 导入工坊。

示例插件启动时仅建立连接，不自动读取或修改媒体。它提供可供自己的菜单、按钮或设置事件调用的方法，不注册演示界面。将源码集成到自己的插件时，依赖配置见 [快速入门](../docs/GETTING_STARTED.md)。

- `MediaExamplePlugin`：连接/停用清理、结构化探测、转码、FFmpeg 高级参数及带租约的 FFprobe 直接执行。`connectionError` 可显示缺少 Runtime 等错误；媒体操作的完成和失败从 `result` 处理，取消使用原任务的 `cancel()`。
- `CapabilityExamples`：播放、持久化网页 Profile、共享系统音频采集、标签读写和原始帧目标。按平台查询能力后，从用户操作调用，媒体输入和输出路径由用户选择。返回的会话由调用方持有，在视图移除或插件停用时关闭。
- Compose 展示使用 [MediaSurface 示例](../docs/USAGE.md#播放与视频帧)。先移除组件，再关闭映射和播放器/目标；不要从 UI 线程同步等待任务。

播放与标签支持 Windows/Linux；网页、采集及渲染目标要求 Windows。网页还需系统 Evergreen Runtime，音频采集受设备和权限限制。`capture.features()` 在首个音频块前可能返回 `CAPTURE_NOT_READY`，此时等待后重新查询；失败会话应关闭后重建。切勿记录 Cookie、认证消息或脚本结果。

示例只打包轻量 SDK、协议与必要序列化依赖，不包含媒体二进制、宿主 Kotlin 或另一套 Compose/Skia。直接执行示例由调用方等待进程并处理退出码；托管调用由 Runtime 提供状态与取消。
