# 第三方组件与构建资料

本项目及 SDK 使用 Apache-2.0。共享依赖保留原许可证，不能从 Runtime 的许可证推断媒体二进制的许可。

| 组件 | 固定版本或来源 | 许可与用途 |
| --- | --- | --- |
| SPW Workshop API / PF4J | 0.1.0-dev23 / 3.12.0 | Apache-2.0；仅编译期引用，宿主提供。 |
| Kotlin / Compose / Skia | 与宿主对齐 | 不在插件中重复打包；独立工作进程复用宿主 Kotlin。 |
| kotlinx.serialization | 1.9.0 | Apache-2.0；轻量控制协议。 |
| JNA | 5.18.1 | LGPL-2.1-or-later / Apache-2.0 双许可；选择 Apache-2.0，位于原生播放工作进程。 |
| jaudiotagger | 3.0.1 | LGPL-2.1-or-later（源码声明）；媒体标签和封面。 |
| FFmpeg / FFprobe | 8.1.3-18-g46d899d7a0，BtbN 2026-10-20 构建 | LGPL shared 变体，保留原有许可与配置资料；不含 libx264/libx265 等 GPL 专用编码器。 |
| libmpv / libplacebo | 0.41.0 / 6.338.2 | LGPL-2.1-or-later；本工程从固定源码以 `gpl=false`、软件帧输出构建，动态链接包内 FFmpeg。Linux 使用 ALSA。 |
| Windows 字体依赖 | FreeType 2.13.3、FriBidi 1.0.16、HarfBuzz 9.0.0、libass 0.17.3 | 分别选择 FTL、LGPL-2.1-or-later、MIT、ISC；静态链接到 libmpv，附对应源码与许可。 |
| Linux 音频、字体等依赖 | Ubuntu 22.04 固定发行仓库 | 具体包版本和来源随工件附 `sysroot-provenance.json`；各包版权声明随包提供。 |
| GCC 支持运行库 | Ubuntu libgcc-s1 | GPL-3.0 加 GCC Runtime Library Exception；不属于 GPL 媒体编码器。 |
| LLVM C++ / MinGW 支持运行库 | Zig 0.15.2 所附源码 | LLVM 使用 Apache-2.0 加 LLVM Exception；MinGW 各文件保留原有许可。匹配运行库源码随源码包提供。 |
| WebView2 SDK | 1.0.3537.50 | Microsoft 再分发条款；仅分发 Loader。Evergreen Runtime 由系统提供。 |
| nlohmann/json | 3.12.0 | MIT；Windows 辅助程序控制协议。 |
| OpenJDK JNI 头文件 | jdk-25+36 | GPL-2.0 with Classpath Exception；仅构建 JVM 工作进程启动器，匹配头文件随源码包提供，不分发另一份 JVM。 |

原生准备脚本与两平台交叉构建配方位于 `native/`。Zig 0.15.2、Meson 1.9.2、Ninja 1.13.1、Python、Mako 1.3.10、Vulkan Headers 1.3.296 等缓存到构建目录，不随 Runtime 作为独立运行环境分发。Windows 系统导入库与资源编译器来自已安装的 Windows SDK；辅助程序使用 MSVC。libmpv 的 DLL/SO 仅在独立工作进程加载。

与 Runtime 二进制匹配的源码和构建资料可从 [Releases](https://github.com/GaBoron/SPW-Media-Runtime/releases) 获取：

- `spw-media-native-sources-<版本>.zip`：FFmpeg/mpv 主项目、libplacebo、Windows 字体依赖、固定 Ubuntu 组件源包、编译器运行库源码及本工程构建配方。原始媒体源包未经改写；资源生成、CRT 数学函数选择与配置字符串兼容处理均由构建脚本记录。
- FFmpeg 依赖源码分卷 [`.001`](https://github.com/GaBoron/SPW-Media-Runtime/releases/download/v0.1.0/spw-media-ffmpeg-dependency-sources-0.1.0.zip.001)、[`.002`](https://github.com/GaBoron/SPW-Media-Runtime/releases/download/v0.1.0/spw-media-ffmpeg-dependency-sources-0.1.0.zip.002)、[`.003`](https://github.com/GaBoron/SPW-Media-Runtime/releases/download/v0.1.0/spw-media-ffmpeg-dependency-sources-0.1.0.zip.003)：BtbN 实际构建保存的完整下载缓存，内含 `cache.tar.gz`，提供 FFmpeg 及其构建依赖的匹配输入。固定记录为 [工作流 39420836791](https://github.com/BtbN/FFmpeg-Builds/actions/runs/39420836791)、提交 `63ac3265b0789ea16c7b1f8351cd2ad195a28722`、资源 `12263308818`；与 `autobuild-2026-10-20-13-08` 对应。构建配方也随主源码归档提供。

FFmpeg/FFprobe 使用上游的 LGPL shared 二进制，不启用 GPL/nonfree 组件；libmpv 从匹配源码构建。配置及包版权随原生资源保留。许可依据包括 [FFmpeg 说明](https://ffmpeg.org/legal.html) 和 [mpv 0.41.0 Copyright](https://github.com/mpv-player/mpv/blob/v0.41.0/Copyright)。DLL/SO 与可执行程序保持可替换的独立文件；替换时必须保持架构、ABI、许可及依赖兼容。未限制对 LGPL 组件的修改或为调试修改进行逆向工程。

当前 FFmpeg 快照对应上述 v0.1.0 依赖源码分卷；本工程原生代码与构建配方使用对应 Runtime 版本的源码归档。下载链接和 CI 缓存存在上游保留期限，应保留本地二进制缓存与匹配源码归档；分发二进制时同时提供这些源码及许可资料，不能用当前 `master` 代替匹配快照。
