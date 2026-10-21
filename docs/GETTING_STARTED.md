# SDK 快速入门

本文面向 SPW 插件作者，适用于 Runtime/SDK 0.2.0、Workshop API 0.1.0-dev23、JDK 25。先安装并启用匹配的 Runtime；SDK 本身不包含媒体实现或原生资源。

## 添加 SDK

在插件工程中配置 Maven Central 和 SDK 依赖。协议和轻量序列化依赖由 Gradle 自动解析，不需要分别下载 JAR。

```kotlin
repositories { mavenCentral() }

val mediaVersion = "0.2.0"
dependencies {
    implementation("io.github.gaboron.spwmedia:spw-media-sdk:$mediaVersion")
    // 需要 Compose 视频帧显示时添加：
    // implementation("io.github.gaboron.spwmedia:spw-media-sdk-compose:$mediaVersion")
    compileOnly(kotlin("stdlib"))
}
```

Workshop 打包器会将 `implementation` 的运行依赖收集到插件 `lib/`，无需再复制一遍 SDK。最终应包含 SDK、协议及轻量序列化依赖。Kotlin、annotations、Workshop API、PF4J、Compose 和 Skia 由宿主提供，需要编译时使用 `compileOnly`。Java 工程可使用 `compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")`。不要使用 `compileOnly` 引入整个 SDK：各插件携带自己的轻量 SDK，跨插件通信不依赖共享 SDK 类型身份。

SDK 发布元数据已排除 Kotlin/annotations。项目的其他依赖仍可能带入宿主库，应检查最终包；不要对所有 Gradle 配置全局排除 annotations，否则可能破坏 Kotlin 编译器自身的依赖。

离线接入或构建尚未发布的版本时，解压匹配的 `spw-media-sdk-<版本>.zip`，在 `mavenCentral()` 前添加 `maven { url = uri("解压后的仓库路径") }`。路径不能指向其他插件安装目录。0.1.x 使用旧坐标 `dev.gaboron.spwmedia`；升级 0.2.0 时统一替换为 `io.github.gaboron.spwmedia`，保持 SDK、协议和 Compose 接入版本一致，代码导入的 `dev.gaboron.spwmedia.*` 包名不变。

## 首次调用

在后台线程连接，使用真实的 `PluginContext.pluginId`。将连接保存到插件对象，在 `stop()` 中关闭。

```kotlin
import dev.gaboron.spwmedia.sdk.MediaRuntime
import dev.gaboron.spwmedia.sdk.MediaRuntimeException
import java.nio.file.Path

// 在插件的后台初始化入口执行，并保存 media。
val media = MediaRuntime.connect(pluginContext.pluginId)
val processing = media.capabilities().firstOrNull { it.id == "processing" }
if (processing?.available == true) {
    val task = media.processing.probe(Path.of("music.flac"))
    task.result.whenComplete { info, failure ->
        if (failure == null) {
            // 将 info 更新到自己的界面；此回调不是 UI 线程。
        } else {
            val detail = MediaRuntimeException.unwrap(failure)?.detail
            // 使用 detail?.code 处理错误，不依赖底层日志。
        }
    }
}
// 插件停用时：media.close()
```

`connect` 和请求提交可能立即抛出 `MediaRuntimeException`，调用入口也应处理 Runtime 缺失、权限或能力错误。异步失败通过 `result` 返回；Java `get()` 和组合调用可能包装异常，统一用 `MediaRuntimeException.unwrap(failure)` 提取详情。取消使用 `task.cancel()`，终态仍从原任务的 `result` 获得；取消 Future 视图不会取消后端。

## 启动顺序与连接恢复

需要有界等待与恢复时，保存 `MediaRuntime.connectWhenAvailable(pluginContext.pluginId)` 返回的 `RuntimeConnection`，在 `stop()` 关闭它。普通同步 `connect()` 继续可用，适合调用方自行控制连接的场景。

连接组件默认每秒尝试一次，单轮最多 30 秒；连接后每 30 秒检查健康状态。`snapshot` 包含状态、代次和最后错误，`events` 是 JDK `Flow.Publisher`；先订阅再读取快照。`current` 仅在连接有效时非空。`UNAVAILABLE` 后调用 `reconnect()` 开启新一轮；事件有界，快照为准。

Runtime 重启后代次增加，旧任务和会话不重放。插件显式创建新播放器或新任务，并丢弃旧代次的迟到界面回调。组件拥有 `current`，不要单独关闭它。

使用 `tools.acquire()` 自建进程时，必须提供 `beforeDisconnect` 回调：停止并等待自己的进程退出后，再完成返回的 `CompletionStage<Void>`。回调在后台执行，默认清理上限 5 秒；失败或超时报告 `CLEANUP_FAILED` 并暂停恢复。未确认安全的租约保护标记保留。完整写法见示例中的 `ExampleConnection` 和 `ExampleOperations.directProbe`。

常用入口为 `processing`、`playback`、`web`、`metadata`、`audio`、`rendering`、`tools`。IDE 可发现真实类型及 KDoc；高级 Provider 扩展使用 `invoke`，不依赖 Runtime 内部类。

## 示例与参考

[SDK 接入示例](../examples/README.md) 是独立 Gradle 消费者，展示异步任务、错误处理、取消、静音播放、主动预览和正确清理。构建与运行步骤由示例说明统一维护。

继续阅读 [使用指南](USAGE.md) 和 [接口契约](CONTRACTS.md)。Maven Central 与离线归档均提供匹配的 `sources.jar`、Dokka `javadoc.jar`；完整接口参考还可从对应 Release 获取。
