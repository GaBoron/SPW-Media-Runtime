# 媒体能力使用指南

适用于 0.2.0。以下所有句柄均属于创建它的连接；插件停用时关闭连接。先查询能力，再调用对应方法。示例使用的 `media` 为已连接的 `MediaRuntime`。

## 媒体处理与底层工具

`processing.probe` 返回容器、流、标签、时长（秒）和文件大小。转码使用 `TranscodeOptions`；`video` 可组合缩放、裁切和滤镜，`extraArguments` 扩展后端选项。输出覆盖默认关闭。相同文件的托管转码输出和元数据写入使用 Core 文件锁协调。

```kotlin
import dev.gaboron.spwmedia.protocol.TranscodeOptions
import java.nio.file.Path

val task = media.processing.transcode(
    TranscodeOptions("input.wav", "output.flac", audioCodec = "flac")
)
// task.events 是 Flow.Publisher；按需求订阅进度。
// 取消用户操作：task.cancel()；最终结果仍从 task.result 获得。
```

`remux` 复制编码流，`extractFrame` 提取指定秒数的图片，`extractAudio` 提取音频。`execute`/`executeProbe` 接受完整参数数组，调用经过 Runtime 的队列、超时和取消处理；不通过 shell。通用执行的输入输出应使用文件，标准输出/错误仅提供有界文本尾部，不用于传输视频或 PCM。高级参数中的输出、复合写入和重命名由调用方安排，避免对同一文件并行修改。

直接执行时使用 `media.tools.acquire("ffprobe")` 或 `"ffmpeg"`。租约返回版本、平台与绝对路径，运行期间保持租约打开。Linux 的 `bin/lib` 布局保留程序的依赖查找关系，不需要猜测路径。自行创建的进程、管道、退出码、超时和进程树由调用插件管理，不会自动生成 RuntimeTask 或进度事件。只有确认进程已退出后才能关闭租约；若终止失败，应保留租约并报告错误，不能无条件 `use`/try-with-resources 提前解除保护。Runtime 断连后停止旧进程，再为新连接重新获取工具；跨 Runtime 重启持续运行旧解码器不属于支持的恢复约定。

托管失败先用 `MediaRuntimeException.unwrap(error)` 解包，再从 `detail` 读取稳定错误码；`detail.backend` 可包含退出码和有界脱敏 stderr 尾部，用于向本插件用户解释编码器或输入失败。不要将它复制到公共日志，也不要从后端文本推断任务是否成功。取消和超时的终态不会被迟到的进程结果覆盖。

## 播放与视频帧

`playback.open(PlayerOptions(...))` 异步创建独立播放器。使用 `load`、`play`、`pause`、`stop`、`seek`、`setSpeed` 和 `setVolume`；时间单位为秒，音量单位为百分比。`command` 与 `setOption` 保留 libmpv 高级能力，选项是否合法由实际 libmpv 版本决定。命令的 `CompletionStage` 成功仅表示后端执行了命令，不能据此认定媒体已加载或开始播放；持续状态和后续失败从 `state()`、`snapshot()` 或 `events` 获取。无实质返回值时忽略 JSON 结果即可。

默认硬件解码选项为 `auto-safe`，不保证当前驱动、格式或输出方式能够硬件解码。两平台均使用 libmpv 软件帧与 JRE 桌面窗口显示；Windows 可将该窗口嵌入调用方拥有的 HWND，父窗口尺寸变化后调用 `resize`。Linux 使用 ALSA，不支持借用 XID 嵌入。

需要自行渲染时，在创建播放器时指定 `frameWidth`、`frameHeight`，通过 `player.frames()` 获取 `SharedBuffer`。四通道帧为 BGRA；分辨率上限 3840×2160。软件渲染和 CPU 上传可能限制帧率，普通显示建议从 960×540 开始。读取方可跳过旧帧，不会要求播放器积压全部帧。

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.gaboron.spwmedia.compose.MediaSurface
import dev.gaboron.spwmedia.sdk.SharedBuffer

@Composable
fun VideoContent(frames: SharedBuffer, modifier: Modifier) {
    MediaSurface(frames, modifier)
}
```

Compose 组件使用宿主的 Skia，不携带另一份 Skiko。先移除显示组件，再关闭 `SharedBuffer`，最后关闭播放器。低层消费使用 `buffer.latest()`；返回的数组属于调用方，关闭映射不会使已经返回的数组失效。`latest()` 面向允许丢帧的最新画面显示，不是逐帧解码队列，也不提供媒体时间戳或定时画布回调。

## 网页与持久化会话

`web.open(WebOptions(...))` 在 Windows 辅助进程的 STA 线程创建 WebView2。`parentWindow = 0` 使用独立窗口；非零值为调用方拥有的 HWND，调用方负责保持父窗口有效并通知 `resize`。网页导航、脚本和消息操作通过异步命令完成，不阻塞 STA 消息泵。

Profile 按插件 ID 和 Profile 名隔离，默认名为 `default`。同插件同 Profile 的多个实例共享 Cookie/Storage，关闭实例及重启宿主后仍保留登录。`clearData()` 明确清除该 Profile 的全部浏览数据，会影响同 Profile 的其他视图。

`evaluate` 返回实际脚本结果对应的动态 `JsonElement`，由调用方按脚本约定读取；它仅执行调用插件提供的可信脚本；传数据优先使用 `postMessage(JsonElement)`，避免字符串插值。页面通过 `window.chrome.webview.postMessage(...)` 发送数据；只有 `allowedOrigins` 中明确允许的 HTTP(S) Origin 会进入 `messages()` 队列。默认不接受页面消息，队列最多保留 128 条，调用方主动读取。Cookie、认证内容和脚本结果不得写入普通日志。

导航错误和浏览器进程异常可从会话状态查询。系统缺少 Evergreen Runtime 时打开浏览器返回 `DEPENDENCY_MISSING`；Runtime 不自动安装或更新系统浏览器环境。

## 元数据、音频和图像

宿主 Library 已提供的曲目、曲库和封面读取，应由调用插件在自己的宿主权限上下文中完成。SDK 不替调用插件绕过 `LIBRARY_READ`。文件级标签读写由 `metadata` 补足：字段名使用 jaudiotagger 的 `FieldKey` 名称，空列表删除字段，未指定字段保持原值。封面通过文件提取/导入，提取目标使用 CREATE_NEW，避免意外覆盖。

`audio.capture` 支持 Windows 默认或指定设备的 `loopback`、`microphone`。相同采集参数和 FFT 大小共享底层源；关闭一个订阅不影响其他订阅。回环捕获系统共享混音，不能保证捕获独占播放或受保护音频。设备不可用、权限不足和采集中断以结构化错误反馈。

`AudioFeatures` 包括线性 RMS/峰值、RMS dBFS、单声道波形及 Hann 窗 FFT 幅度；不是 LUFS 或校准声压，静音 dBFS 下限为 -160。FFT 大小为 64..32768 的二次幂。`audio.analyze` 接受调用方准备的 float32 little-endian 交错 PCM 文件；采样率、声道数必须显式给出。实时 PCM 经 `samples()` 的有界映射交付。

`rendering.createTarget` 创建 Windows BGRA/RGBA 目标，`present` 从调用方文件发布一整帧，`convert` 转换两种四通道顺序。目标、映射和生产文件各有明确所有权；Runtime 不删除调用方文件。
