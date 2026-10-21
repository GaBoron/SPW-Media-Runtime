# 贡献指南

感谢你关注 SPW Media Runtime。本项目欢迎功能实现、平台适配、公共 API、开发文档及使用示例等方面的贡献。

本项目作为多个 Salt Player for Windows 创意工坊插件共同依赖的运行时，对**兼容性、依赖共享、可扩展性和开发者体验**有较高要求。贡献规范适用于任何相关变更，不局限于特定开发阶段或预先列出的模块。提交 Pull Request 前，请根据改动范围阅读对应规范。

## 贡献范围与参考规范

| 变更类型 | 参考文档 |
| --- | --- |
| Runtime Core、模块注册、通信协议、生命周期、并发及原生依赖 | [架构贡献规范](docs/ARCHITECTURE.md) |
| SDK 与公共 API 的能力设计、易用性和兼容性 | [公共 SDK 设计与审查规范](docs/SDK_DESIGN.md) |
| KDoc、教程、API Reference、错误说明和 Examples | [开发者体验与文档规范](docs/DEVELOPER_EXPERIENCE.md) |
| 模块实现与平台适配 | 三份规范均适用 |

README 介绍项目定位和规划功能，不代替具体的接口契约或贡献规范。

## 变更原则

### 保持下游兼容

优先以兼容方式增加能力。修改公开接口、错误语义、模块标识或版本协商机制时，应评估对现有插件的影响，并提供必要的迁移说明。公共依赖由 Runtime 集中维护，不应要求下游插件重新打包大型媒体组件。接入方式不限于高级 SDK：对适合直接使用的底层程序或组件，应保留公开、可发现的访问途径。

### 通过既有扩展机制演进

新增媒体能力应优先新增独立 Provider，而不是把具体模块逻辑接入 Runtime Core。若改动需要扩展基础契约，请在 PR 中说明原因、替代设计与兼容性影响。

### 明确生命周期和故障边界

所有涉及原生资源、任务或会话的改动，都应说明资源由谁持有、何时释放、多插件并发时如何隔离，以及调用方如何获知失败。敏感账户凭据不得写入普通日志。

### 同步交付公开接口与文档

改变开发者可见行为时，相关 API Reference、使用说明及适用的示例应与实现同步更新。文档应优先展示易于调用、覆盖充分的 SDK 接口，并准确说明高级参数调用与受支持的底层访问方式；示例以实际需求组织，不预设固定工程清单。未实现或无法运行的能力应如实标注，不以规划中的接口作为正式用法。

## 提交 Pull Request

PR 标题应概括实际变更。描述建议涵盖以下信息，并按变更规模取舍：

1. **背景与范围**：要解决什么问题，涉及哪些模块或接口。
2. **设计与取舍**：为何采用该实现；对 Core、SDK 和依赖分发的影响。
3. **兼容性与平台**：对现有插件、版本及支持平台有何影响。
4. **资源与异常路径**：并发、生命周期、错误反馈及安全性如何处理。
5. **验证与文档**：已确认的行为、已知限制，以及更新的参考文档或示例。

对于仅修改错别字等轻量变更，无需机械填写所有项目；涉及核心契约、共享资源和公共 API 时，应提供足够信息支持维护者审查。

## 文档与示例贡献

文档以便于开发者完成任务为优先，要求准确、简洁、易于定位，并提供充分的技术细节。应根据使用场景提供 SDK 与底层组件调用示例，尽可能独立且可运行，不要求读者从 Runtime 源码中寻找缺失步骤。

新增模块的公开接入文档可随实现逐步完善，但在标记为稳定可用之前，须满足 [开发者体验与文档规范](docs/DEVELOPER_EXPERIENCE.md) 中的交付要求。

## 第三方组件与许可

引入或更新第三方组件时，请说明来源、版本、平台支持、构建/分发方式及许可证义务。原生库兼容性、重复打包和许可证限制均属于审查范围。项目自身及第三方组件的许可要求以仓库当前文件及实际分发方式为准。

## 构建工程

JVM 工程使用 Gradle Wrapper 与 JDK 25。修改 SDK、协议或示例时，先运行对应的构建与检查：

```powershell
.\gradlew.bat checkSdkArtifacts consumerFixture checkExample
```

这些任务不需要 Central 账号或签名材料，也不准备 Runtime 原生组件。涉及完整 Runtime 交付时，在 Windows x64 上运行以下命令；原生构建需要 PowerShell 7、Windows SDK 与 MSVC x64。Linux 工件由便携 Zig、Meson、Ninja 与固定 Ubuntu 22.04 sysroot 交叉构建，不要求安装 WSL。

```powershell
.\gradlew.bat delivery
```

首次构建需要联网下载固定版本的媒体资源与构建工具，之后可复用 `build/native-cache/`。`native/prepare.ps1 -WindowsOnly` 或 `-LinuxOnly` 可单独准备平台资源。完整 `plugin` 任务检查两平台必需资产，不生成缺失组件的通用包。

`nativeSources` 还会收集匹配的媒体依赖源码。FFmpeg 上游源码缓存通过已登录的 GitHub CLI 获取，或由环境变量 `SPW_MEDIA_FFMPEG_SOURCES` 指向已保存的 `cache.tar.gz` ZIP；固定构建记录及本地归档名称见 [第三方组件](third-party/README.md)。离线构建须保留该归档与构建缓存。

`nativeManifest` 生成依赖闭包与内容标识。`sdkBundle` 将当前版本的四个公共模块发布到 `build/maven/` 并生成离线仓库归档；归档不混入旧坐标、旧版本或 Runtime 实现。源码与 Dokka Javadoc 随模块提供，独立接口参考由 `apiBundle` 生成。

`examplePlugin` 调用独立 `examples` 工程，包输出到 `examples/build/distributions/`；`checkExample` 只编译示例。示例面向阅读和手动体验，不承担回归断言，也不进入默认发布资产。

协议、Core 与 SDK 回归测试位于各自 `src/test/`。运行 `./gradlew.bat :protocol:test :runtime-core:test :sdk:test`，无需运行 SPW 或完整原生构建。`checkSdkArtifacts` 进一步检查四个公共模块的 POM、真实 Sources/Javadoc、SDK 归档及宿主依赖边界；它可独立运行，不要求签名或发布凭据。

准备原生资源后运行 `./gradlew.bat consumerSmoke`。验证器以两个独立类加载器加载专用 Java 消费者及各自的 SDK，直接断言结构化结果、错误码、帧和资源状态；不依赖示例配置页或提示文案。它覆盖迟启动、真实媒体任务、租约工具、取消、静音播放、单方停用隔离、重启恢复和进程线程清理。可用 `"-PsmokeJavaHome=<宿主运行时目录>"` 指定工作进程 JVM；不打开预览、网页或音频采集。`consumerFixture` 只构建独立消费者并验证公共 Maven 依赖，不执行原生媒体。

`delivery` 执行各模块检查、示例编译和原生消费者验证，并在 `build/distributions/` 生成 Runtime、SDK、接口参考及源码归档。修改依赖或打包逻辑时，应检查生成包的组件范围及宿主依赖边界。

涉及图形、音频或插件生命周期的 PR，应说明真实 SPW 中的验证范围；构建和自动检查不能代替这些验收。

## 缓存维护

调试原生资源缓存问题时，使用当前源码对应版本的维护命令。默认仅预览，不回收文件：

```powershell
.\gradlew.bat cacheCleanup
```

从预览选择一个完整代次标识后，显式回收：

```powershell
.\gradlew.bat cacheCleanup -PcacheGeneration=<完整代次标识> -PapplyCleanup=true
```

可用 `-PruntimeDataDir=<目录>` 指定数据目录。当前构建版本、活动租约、仍存活的工作进程或归属不明的目录均跳过。Windows 使用构建目录中的静默回收辅助程序，Linux 需要 `gio trash`；回收失败保留原路径，不降级为永久删除。旧版没有代次元数据的缓存仅提示保留，须在确认所有宿主与直接调用程序退出后人工检查。
