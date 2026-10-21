# 0.2.0 接口契约

适用于 SDK/API 0.2.0、控制协议 1。API Reference 由公开源码和 KDoc 生成；本文说明跨模块共用的行为。

## 版本与发现

SDK 是每个调用插件自己的轻量依赖。Runtime 注册 JVM 本地 JMX 对象 `dev.gaboron.spwmedia:type=Runtime,protocol=1`，不启用远程 JMX。跨类加载器只传递 JDK String，内容为版本化 JSON；SDK 类型与 Provider 实现类型不跨插件强制转换。

协议版本、SDK/API 版本、Provider 版本与原生依赖版本分别声明。调用方按能力 ID、功能列表和可用性决定是否调用，不从总版本推测功能。新增未知 JSON 字段被忽略；能力、状态和错误标识为可扩展字符串，未知值应按不可识别能力/状态处理，不当作成功。

SPW 适配通过 PF4J 公开类加载器 URL 与其插件 Manifest 识别调用方，拒绝不匹配的插件 ID。连接令牌及资源归属保护正常插件之间的访问边界；同 JVM 的任意原生代码不是安全沙箱，不声称防御恶意插件修改宿主内存或其他插件文件。

## 任务、会话与线程

- `connect`、能力查询和请求提交是同步控制调用，应从后台线程调用。媒体操作在 Runtime 有界队列中执行，SDK 后台线程交付完成和事件。
- 默认全局并发 4、排队上限 128；每连接最多 32 个活动任务、64 个资源。资源超限返回 `RESOURCE_LIMIT`。空闲队列不保留持续工作的线程。
- 任务状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`TIMED_OUT`；终止状态不回退。进度可为空，未知媒体时长不伪造百分比。
- 一次调用产生一个操作 ID；重试产生新操作，不承诺自动去重。除查询外，不自动重试可能有副作用的操作。
- 取消和超时会终止托管进程树；对已经提交给长会话的命令，取消不能撤销已发生的播放、导航或文件写入。结果和错误从任务 `result` 获得。
- 每连接保留有界近期任务结果；达到 256 条时回收部分已完成记录。长会话应使用状态查询和事件订阅，不依赖无限历史。
- 进度事件是有界、允许合并/丢弃中间值的通知；最终结果以 `result` 为准。慢的会话订阅者按请求数量接收当前快照。

`close`、取消及资源释放可重复调用。释放失败的句柄禁止继续业务操作，但允许再次 `close()`；Core 在释放成功后才移除登记。客户端清理失败时进入 `CLIENT_CLOSING`，再次关闭以重试；确认 Runtime 已失效后可完成本地关闭。关闭客户端释放其任务、播放器、浏览器、映射和租约，保留持久化浏览器 Profile；其他连接及共享采集订阅继续有效。插件必须在 `stop()` 关闭客户端，Runtime 停用时清理剩余连接。被强制终止的 JVM 无法承诺执行应用级清理。

## 数据与原生资源

控制请求上限 1 MiB。视频与 PCM 使用共享映射的最新完整块，容量有界，以序列号检查并发写入；读取不到稳定帧时返回 null，不积压旧帧。SDK 返回独立数组，映射由调用方关闭，生产端由会话关闭。

能力查询检查安装资源，不准备原生缓存。实际调用按构建清单准备资源及其关联依赖，同一代次复用公共 DLL/SO；代次由资源内容标识，不使用安装文件的修改时间。准备有锁和完整性检查，全部所需文件就绪后才返回路径，不覆盖既有文件。发现损坏缓存时返回错误，由维护者在停止使用后检查处理。

共享程序在 Runtime 数据目录的版本化缓存中固定路径，Runtime 租约和工作进程记录保护活动代次。直接执行程序的调用方须在进程结束后再释放租约；未明确释放的工具租约在连接失效或 Runtime 停用时视为进程归属未知，维护命令保留对应缓存保护标记，不以断连推断自有进程已经退出。缓存不依赖系统 PATH，底层动态库不作为 SDK 公共 ABI 暴露，也不承诺卸载已加载 DLL/SO。WebView2 可用性查询可运行短时依赖检查，不启动持续运行的浏览器会话。

播放工作进程由随包的原生启动器通过 JNI 复用宿主 JVM 运行时，不依赖 `java.exe`、系统 Java 或完整 JDK。libmpv 仅在该子进程内加载。

持久化数据默认位于 Windows `%LOCALAPPDATA%/SPW Media Runtime` 或 Linux `$XDG_DATA_HOME/spw-media-runtime`（缺省 `~/.local/share/spw-media-runtime`）。临时映射按运行实例记录归属，关闭后静默移入回收站；失败记录待回收状态。启动时重试确认所属宿主和工作进程已退出的残留，每次处理最多 16 个旧运行目录。无可用回收机制时保留文件，不永久删除。旧版无归属元数据的映射不会自动回收。

Windows 静默回收使用随包提供的辅助程序；Linux 使用系统 `gio trash`，不可用时保留并报告。旧版本原生缓存不在启动时自动清理，显式预览和回收命令见 [贡献指南](../CONTRIBUTING.md#缓存维护)。不要在资源使用期间手工清理数据目录。

## 错误与诊断

错误包括稳定 `code`、可读消息、插件/操作/会话标识和 `retryable`。不要求解析底层异常文本，也不在普通日志输出媒体 URL 凭据、Cookie、访问令牌或脚本结果。

`RuntimeError.backend` 为可选的所属调用方详情，包含后端名、可用时的退出码和最多 8 KiB UTF-8 的脱敏 `stderrTail`。缺少尾部文本不代表后端成功；错误处理仍以 `code` 为准。失败详情不会进入普通日志或 `diagnostics()`。控制协议仍为 1，旧 SDK 忽略新增字段；各插件继续携带自己版本配套的 SDK 与协议，不要单独替换协议 JAR。

`media.diagnostics()` 返回本插件的近期脱敏故障，包含时间、错误码和关联标识；Runtime 内存历史上限为 256 条，停用后清空。该入口不返回其他插件的诊断，不记录路径、URL、Cookie 或后端输出。

| 错误码 | 调用方处理 |
| --- | --- |
| `RUNTIME_UNAVAILABLE` / `CLIENT_CLOSED` | 启用 Runtime，按插件生命周期重新连接。 |
| `CLIENT_CLOSING` / `RESOURCE_CLOSE_FAILED` | 清理失败时不要提交新业务，再次关闭以重试。 |
| `CLEANUP_FAILED` | 自有资源清理失败或超时；停止自己的进程后显式重连。 |
| `PROTOCOL_INCOMPATIBLE` | 使用支持协议 1 的 SDK/Runtime 组合。 |
| `UNSUPPORTED_PLATFORM` / `CAPABILITY_UNSUPPORTED` | 查询能力并禁用相关功能。 |
| `DEPENDENCY_MISSING` | 检查完整 Runtime 包或安装 WebView2 Evergreen Runtime。 |
| `INVALID_ARGUMENT` / `OPERATION_UNSUPPORTED` | 核对当前版本公开签名、参数和后端选项。 |
| `RESOURCE_LIMIT` | 关闭不用的资源，减少并发，稍后重试。 |
| `PERMISSION_DENIED` | 核对插件身份、文件权限或系统音频隐私授权。 |
| `MEDIA_PROCESS_FAILED` / `PLAYBACK_FAILED` | 检查输入媒体、编码器或播放设备；不要盲目重试写操作。 |
| `BACKEND_START_FAILED` / `BACKEND_EXITED` / `BACKEND_FAILED` / `BACKEND_TIMEOUT` / `RENDER_FAILED` | 检查完整安装及所属调用方的后端详情；关闭失败会话，在能力允许时创建新会话。 |
| `SESSION_CLOSED` | 会话已关闭；关闭中的待完成命令也会终止，不再向旧会话提交命令。 |
| `CAPTURE_NOT_READY` / `CAPTURE_UNAVAILABLE` | 等待首个音频块，或检查默认设备与采集模式。 |
| `FILE_NOT_FOUND` / `IO_FAILED` / `ARTWORK_NOT_FOUND` | 核对路径、文件状态和封面索引。 |
| `METADATA_READ_FAILED` | 核对标签后端支持的音频格式与文件完整性；无音轨视频不作为音频标签样本。 |
| `CANCELLED` / `TIMED_OUT` | 视为终止，检查操作可能已经产生的输出后决定是否重试。 |

0.2.0 继续使用控制协议 1，既有 0.1.x 消费者可连接。升级 SDK 时将 Maven groupId 从 `dev.gaboron.spwmedia` 改为 `io.github.gaboron.spwmedia`，同步升级 SDK/协议/Compose 依赖；代码包名不变。旧版工具调用方同样需要在自有进程退出后显式释放租约，否则可能留下保守保护标记。项目仍处于初始开发阶段。

`RuntimeConnection` 是可选组件，不改变普通 `connect()` 的同步行为。默认连接轮次上限 30 秒、重试间隔 1 秒、健康检查 30 秒、清理上限 5 秒。只有新连接成功才增加 generation；清理失败暂停恢复，超时轮次不无限重试。重建连接不重放任务、恢复旧会话或复用旧令牌。
