# SDK 快速入门

本文面向 SPW 插件作者，适用于 Runtime/SDK 0.1.1、Workshop API 0.1.0-dev23、JDK 25。先安装并启用 Runtime；SDK 本身不包含媒体实现或原生资源。

## 添加 SDK

解压 `spw-media-sdk-0.1.1.zip`，将其作为 Maven 仓库。以下路径由开发者选择，不能指向其他插件的安装目录。

```kotlin
repositories {
    maven { url = uri("path/to/unpacked-sdk-repository") }
    mavenCentral()
}
dependencies {
    implementation("dev.gaboron.spwmedia:spw-media-sdk:0.1.1")
    // 需要 Compose 视频帧显示时再添加：
    // implementation("dev.gaboron.spwmedia:spw-media-sdk-compose:0.1.1")
}
```

将 SDK、协议和其轻量序列化依赖放入调用插件的 `lib/`。Kotlin、Workshop API、PF4J、Compose 和 Skia 由宿主提供，使用 `compileOnly`，不要重复打包。不要使用 `compileOnly` 引入整个 SDK：各调用插件应携带自己的轻量 SDK 副本，跨插件通信使用 JDK 本地接口，不依赖共享 SDK 类型身份。

## 首次调用

在后台线程连接 Runtime，使用真实的 `PluginContext.pluginId`。连接应保存到插件对象中，并在 `stop()` 中关闭。

```kotlin
import dev.gaboron.spwmedia.sdk.MediaRuntime
import dev.gaboron.spwmedia.sdk.MediaRuntimeException
import java.nio.file.Path

val media = MediaRuntime.connect(pluginContext.pluginId)
val processing = media.capabilities().firstOrNull { it.id == "processing" }
if (processing?.available == true) {
    val task = media.processing.probe(Path.of("music.flac"))
    task.result.whenComplete { info, failure ->
        if (failure == null) {
            // 将 info 更新到自己的界面；此回调不是 UI 线程。
        } else {
            val detail = (failure as? MediaRuntimeException)?.detail
            // 使用 detail.code 处理错误，不依赖底层日志。
        }
    }
}
// 插件停用时：media.close()
```

`connect` 和请求提交可能立即抛出 `MediaRuntimeException`，因此也应在调用入口处理缺失 Runtime、权限或能力错误。异步操作中的失败通过 `result` 返回。使用 `CompletionStage` 的组合操作后，失败可能包装在 `CompletionException` 中，读取其 cause 即可。

插件可以先于 Runtime 启用：`RUNTIME_UNAVAILABLE` 后允许在后台重试 `MediaRuntime.connect(pluginContext.pluginId)`，也可以提供显式连接按钮。失败连接不会保留会话。Runtime 停用并重新启用后，旧令牌失效；关闭旧客户端并再次调用 `connect`，不需要重新加载调用插件。不要自动重试转码、标签写入或其他媒体操作。两个可安装示例展示有界等待和显式重连。

常用入口包括 `processing`、`playback`、`web`、`metadata`、`audio`、`rendering` 与 `tools`。IDE 可从真实类型、参数及 KDoc 中发现方法；高级 Provider 扩展使用 `invoke`，无需依赖 Runtime 内部类。

## 示例与参考

`examples` 提供两个带 SPW 配置页的消费者：功能示例展示 SDK 操作和主动视频预览；独立并发示例只从生成的 Maven 仓库引用 SDK。运行 `./gradlew.bat examplePlugin parallelExamplePlugin` 后导入两个示例包。启动仅连接和查询能力，不自动处理媒体、出声或打开预览；操作步骤见 [示例说明](../examples/README.md)。

继续阅读 [使用指南](USAGE.md) 和 [接口契约](CONTRACTS.md)。完整签名与 KDoc 通过 Dokka 生成，SDK 归档同时包含对应的 `sources.jar`。
