# SDK 接入示例

适用于 Runtime/SDK 0.1.1、SPW Workshop API dev23 与 JDK 25。两个插件各自携带轻量 SDK，通过同一个 Runtime 工作，均在工坊的插件配置页提供操作入口。

## 构建与安装

在仓库根目录运行：

```powershell
.\gradlew.bat examplePlugin parallelExamplePlugin
```

将 `build/distributions/` 中的 Runtime、`spw-media-example-<版本>.spmod` 和 `spw-media-parallel-example-<版本>.spmod` 导入工坊。先启用 Runtime，再启用两个示例。

宿主重启时，示例会等待 Runtime 启用，最多 30 秒，以适应插件启动顺序；仍未连接时可点“重新连接 Runtime”。此操作不自动重建媒体会话或重试写操作。

| 插件 | 用途 |
| --- | --- |
| SPW Media Example | 探测、转码、取消、静音播放、取帧、主动预览，以及标签、网页、音频和渲染目标调用。 |
| SPW Media Parallel Example | 独立 Gradle 工程，只引用生成的 Maven SDK；展示多个插件分别持有转码任务与播放器的接入方式。 |

独立工程也可以直接引用解压后的 SDK 仓库构建：

```powershell
.\gradlew.bat -p examples/parallel-consumer -PsdkVersion=0.1.1 -PsdkRepository=<SDK仓库绝对路径> plugin
```

依赖接入细节见 [快速入门](../docs/GETTING_STARTED.md)。两个包不携带原生媒体组件、宿主 Kotlin 或另一份 Compose/Skia。

## 配置页与首次使用

配置页按用途分组：媒体文件、转码导出、播放与预览、音频与标签、网页与图像，以及连接与会话。并发示例保留媒体文件、转码、播放与视频帧、连接与会话四组，各项设置和操作归本插件独立持有。

1. 在“媒体文件”中填写自己的本地媒体完整路径。没有现成媒体时，可留空路径并点击“创建演示素材”：生成 2 秒无声合成视频，功能示例还会生成带 TITLE 标签的静音 FLAC。
2. 点击“读取媒体信息”查看容器、时长或流信息；功能示例的“导出 MP4 视频”生成独立的无声视频。输出位于本示例的临时目录，不覆盖输入或现有文件，完成后可查看输出路径。
3. “按实时速度转码”循环读取输入，按播放速度导出最多 120 秒的无声 MP4。可通过“取消当前任务”结束，最终结果显示 `CANCELLED`。
4. 在播放组点击“开始静音播放”，随后可“读取当前视频帧”。播放器循环提供共享帧，不自动打开窗口或播放声音。
5. 在功能示例中主动点击“打开视频预览”显示 `MediaSurface`。关闭预览依次释放显示、读映射和该播放器；再次预览前重新创建播放器。“关闭本插件会话”也可释放未预览的播放器。

在“连接与会话”中点击“查看最近结果”读取当前操作结果或错误；耗时任务完成后可再次查看。结果通过只读弹窗显示。该组还提供重新连接和关闭本插件会话的入口。

启动仅连接和查询能力，不自动处理媒体。最近成功生成的视频路径会保存到本插件配置，重启后输入路径留空仍可使用；临时文件已被系统清理时提示重新创建素材。生成的媒体与转码结果留在所显示的临时目录，供查看后手动回收。

## 并发与停用

两个示例各自持有 SDK 客户端、任务和播放器，可分别处理不同输入并同时工作。关闭或停用一个示例只释放其拥有的资源；再次启用时建立新连接。

Runtime 停用会终止已有任务和会话。重新启用后，在示例配置页点“重新连接 Runtime”或重新启用示例，再按需创建媒体会话；不复用旧令牌或旧播放器。

## 用法导览

| 场景 | 代码入口 | 展示内容 |
| --- | --- | --- |
| 能力发现与重连 | `ExampleOperations.connect`、`ParallelOperations.connect` | 迟启动、有界连接等待、新客户端替换旧令牌。 |
| 探测、转码与取消 | `ExampleOperations`、`MediaExamplePlugin.convert/custom` | 类型化选项、进阶 FFmpeg 参数、任务完成与错误反馈。 |
| 播放与帧显示 | `ExampleOperations.play/frames/preview`、`ExamplePreview` | 静音共享帧、限时等待首帧、Compose 显示及关闭顺序。 |
| 标签与封面 | `CapabilityExamples`、`media.metadata` | 音频标签读取、明确用户操作下的写入及封面文件传输。 |
| 网页会话 | `WebProfileExample`、`CapabilityExamples.browser`、`ExampleOperations.web` | 持久 Profile、Cookie 后导航、可信脚本、JSON 消息与允许来源。 |
| 音频分析与采集 | `CapabilityExamples.analyzePcm/capture`、`ExampleOperations.audio` | 文件分析、共享采集订阅、RMS/峰值/FFT，关闭单个订阅。 |
| 渲染目标 | `CapabilityExamples.image`、`ExampleOperations.render` | 文件传输像素、发布共享帧及独立消费映射。 |
| 直接工具调用 | `MediaExamplePlugin.directProbe` | 自行启动 FFprobe、保留工具租约、停用时先终止进程。 |

## 其他能力与资源约定

标签读取在输入留空时使用生成的 FLAC，也可填写 jaudiotagger 支持的音频路径；无音轨视频及不受支持或损坏的文件可能返回 `METADATA_READ_FAILED`。标签写入示例仍在 `CapabilityExamples` 中，仅从明确的用户操作调用。网页按钮主动打开空白 WebView2，关闭会话保留本插件 Profile；不自动访问网站或读写 Cookie。回环采集第一次点击创建会话，后续点击读取 RMS、峰值与采样率，不写出采集音频。

播放与标签支持 Windows/Linux；网页、采集及渲染目标要求 Windows。网页还需系统 Evergreen Runtime，音频采集受设备和权限限制。`capture.features()` 在首个音频块前可能返回 `CAPTURE_NOT_READY`，此时等待后重新查询；失败会话应关闭后重建。切勿记录 Cookie、认证消息或脚本结果。

`CapabilityExamples` 还展示新音频导出后写入标签、封面导出/替换和像素格式转换等流程，调用参数由使用者提供。`MediaExamplePlugin.directProbe` 展示底层工具租约。直接执行由调用方负责进程、管道、退出码和取消；托管调用从原任务的 `result` 获取成功、失败、取消和超时。不要从 UI 线程等待完成，也不要通过后端文本判断成功。

关闭顺序为显示组件、消费者 `SharedBuffer`、生产会话，最后关闭 `MediaRuntime`。示例使用 [MediaSurface 接口](../docs/USAGE.md#播放与视频帧)，没有更换 Runtime 的渲染路径。构建和包结构检查不能代替 SPW 中的图形、音频及重启验收。
