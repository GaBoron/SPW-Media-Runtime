"""Build the Windows LGPL libmpv from pinned sources and reuse the packaged FFmpeg shared libraries."""
from pathlib import Path
import argparse
import json
import os
import shutil
import sys

sys.path.insert(0, str(Path(__file__).parent.parent / 'linux'))
from build import download, extract, python_dependency, run


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    common = args.cache.resolve() / 'linux-build'
    common.mkdir(parents=True, exist_ok=True)
    cache = args.cache.resolve() / 'windows-mpv-source-build'
    cache.mkdir(exist_ok=True)
    python_dependency('MarkupSafe', '3.0.3', common)
    python_dependency('Mako', '1.3.10', common)
    zig = extract(download('https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip', common / 'zig-0.15.2.zip'), common / 'zig') / 'zig-x86_64-windows-0.15.2' / 'zig.exe'
    ninja = extract(download('https://github.com/ninja-build/ninja/releases/download/v1.13.1/ninja-win.zip', common / 'ninja-1.13.1.zip'), common / 'ninja')
    meson = extract(download('https://github.com/mesonbuild/meson/releases/download/1.9.2/meson-1.9.2.tar.gz', common / 'meson-1.9.2.tar.gz'), common / 'meson') / 'meson-1.9.2/meson.py'
    vulkan = extract(download('https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.296.tar.gz', common / 'vulkan-headers-1.3.296.tar.gz'), common / 'vulkan-headers') / 'Vulkan-Headers-1.3.296/include'
    specifications = [
        ('freetype', 'https://github.com/freetype/freetype/archive/refs/tags/VER-2-13-3.tar.gz', 'freetype-2.13.3.tar.gz', 'freetype-VER-2-13-3'),
        ('fribidi', 'https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz', 'fribidi-1.0.16.tar.xz', 'fribidi-1.0.16'),
        ('harfbuzz', 'https://github.com/harfbuzz/harfbuzz/archive/refs/tags/9.0.0.tar.gz', 'harfbuzz-9.0.0.tar.gz', 'harfbuzz-9.0.0'),
        ('libass', 'https://github.com/libass/libass/archive/refs/tags/0.17.3.tar.gz', 'libass-0.17.3.tar.gz', 'libass-0.17.3'),
        ('libplacebo', 'https://github.com/haasn/libplacebo/archive/refs/tags/v6.338.2.tar.gz', 'libplacebo-6.338.2.tar.gz', 'libplacebo-6.338.2'),
        ('mpv', 'https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz', 'mpv-0.41.0.tar.gz', 'mpv-0.41.0'),
    ]
    sources = {}
    for name, url, archive, directory in specifications:
        sources[name] = extract(download(url, cache / archive), cache / (name + '-source')) / directory
    # Static archives do not carry a Windows DLL version resource; disable only that resource generation.
    resource_build = sources['libplacebo'] / 'src/meson.build'
    value = resource_build.read_text()
    needle = "if host_machine.system() == 'windows'\n  windows = import('windows')"
    patched = value.replace(needle, "if host_machine.system() == 'windows' and get_option('default_library') != 'static'\n  windows = import('windows')")
    # Use Zig's matching CRT math instead of manufacturing an optional alternate UCRT import library.
    patched = patched.replace("if host_machine.system() == 'windows' and mingw32 != ''", "if false and host_machine.system() == 'windows' and mingw32 != ''")
    if patched != value:
        resource_build.write_text(patched)
    freetype_build = sources['freetype'] / 'meson.build'
    value = freetype_build.read_text()
    patched = value.replace("    winmod.compile_resources('src/base/ftver.rc'),", "    # Static FreeType is linked into libmpv; it has no independent DLL version resource.")
    if patched != value:
        freetype_build.write_text(patched)
    mpv_build = sources['mpv'] / 'meson.build'
    value = mpv_build.read_text()
    patched = value.replace("'--codepage=65001'", "'/c65001'")
    if patched != value:
        mpv_build.write_text(patched)
    resource = sources['mpv'] / 'osdep/mpv.rc'
    value = resource.read_text()
    patched = value.replace('GNU General Public License Version 2 or later.', 'GNU Lesser General Public License Version 2.1 or later.')
    if patched != value:
        resource.write_text(patched)
    tools = cache / 'tools'
    tools.mkdir(exist_ok=True)
    for name, command in [('cc', 'cc'), ('cxx', 'c++'), ('ar', 'ar')]:
        headers = f' -I"{vulkan}"' if command in ('cc', 'c++') else ''
        (tools / (name + '.cmd')).write_text(f'@"{zig}" {command}{headers} %*\n')
    (tools / 'python3.cmd').write_text(f'@"{sys.executable}" %*\n')
    pkg = tools / 'pkg-config.cmd'
    pkg.write_text(f'@"{sys.executable}" "{Path(__file__).parent.parent / "linux/pkgconfig.py"}" %*\n')
    prefix = cache / 'prefix'
    pc = cache / 'pkgconfig'
    pc.mkdir(exist_ok=True)
    ffmpeg = args.cache.resolve() / 'windows-media/ffmpeg-8.1.3/ffmpeg-n8.1.3-18-g46d899d7a0-win64-lgpl-shared-8.1'
    for name, version, library in [('libavcodec','62.0.0','avcodec'),('libavfilter','11.0.0','avfilter'),('libavformat','62.0.0','avformat'),('libavutil','60.0.0','avutil'),('libswresample','6.0.0','swresample'),('libswscale','9.0.0','swscale')]:
        (pc / (name + '.pc')).write_text(f'Name: {name}\nDescription: shared FFmpeg\nVersion: {version}\nLibs: -L"{(ffmpeg / "lib").as_posix()}" -l{library}\nCflags: -I"{(ffmpeg / "include").as_posix()}"\n')
    def dependency(name, version, libraries, include='include', definitions=''):
        (pc / (name + '.pc')).write_text(f'Name: {name}\nDescription: pinned static dependency\nVersion: {version}\nLibs: -L"{(prefix / "lib").as_posix()}" {libraries}\nCflags: -I"{(prefix / include).as_posix()}" {definitions}\n')
    cross = cache / 'cross.ini'
    kits = Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')) / 'Windows Kits/10'
    sdk_version = sorted((kits / 'Lib').iterdir())[-1].name
    sdk_lib = kits / 'Lib' / sdk_version / 'um/x64'
    resource_compiler = kits / 'bin' / sdk_version / 'x64/rc.exe'
    cross.write_text(f'''[binaries]
c = ['{(tools / 'cc.cmd').as_posix()}']
cpp = ['{(tools / 'cxx.cmd').as_posix()}']
ar = ['{(tools / 'ar.cmd').as_posix()}']
pkg-config = ['{pkg.as_posix()}']
windres = ['{resource_compiler.as_posix()}']
[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
[properties]
needs_exe_wrapper = false
[built-in options]
c_args = ['-target', 'x86_64-windows-gnu']
cpp_args = ['-target', 'x86_64-windows-gnu']
c_link_args = ['-target', 'x86_64-windows-gnu', '-lc++', '-L{sdk_lib.as_posix()}']
cpp_link_args = ['-target', 'x86_64-windows-gnu', '-L{sdk_lib.as_posix()}']
''')
    env = os.environ.copy()
    env['PATH'] = str(tools) + os.pathsep + str(ninja) + os.pathsep + env['PATH']
    env['PYTHONPATH'] = str(common / 'python-packages')
    env['PKG_CONFIG_PATH'] = str(pc) + os.pathsep + str(prefix / 'lib/pkgconfig')
    env['ZIG_GLOBAL_CACHE_DIR'] = str(common / 'zig-cache')
    env['ZIG_LOCAL_CACHE_DIR'] = str(cache / 'zig-local-cache')
    env['CC_FOR_BUILD'] = str(tools / 'cc.cmd')
    env['CXX_FOR_BUILD'] = str(tools / 'cxx.cmd')
    env['AR_FOR_BUILD'] = str(tools / 'ar.cmd')
    env['INCLUDE'] = os.pathsep.join(str(kits / 'Include' / sdk_version / folder) for folder in ('um', 'shared', 'ucrt'))
    def setup(name, flags):
        target = cache / (name + '-build')
        signature = json.dumps({'flags': flags, 'cross': cross.read_text(), 'recipe': Path(__file__).read_text()})
        marker = target / 'completed-inputs.json'
        if marker.exists() and marker.read_text() == signature:
            return target
        command = [sys.executable, meson, 'setup', target, sources[name], '--cross-file', cross, '--prefix', prefix.as_posix(), '--libdir', 'lib', '--buildtype', 'release', '--wrap-mode', 'nodownload', '-Ddefault_library=static', *flags]
        if (target / 'build.ninja').exists():
            command += ['--reconfigure', '--clearcache']
        run(*command, env=env)
        config = target / 'config.h'
        if name == 'mpv' and config.exists():
            import re
            config.write_text(re.sub(r'^#define CONFIGURATION .*$', lambda _: '#define CONFIGURATION ' + json.dumps('windows portable-cross ' + ' '.join(flags)), config.read_text(), flags=re.MULTILINE))
        run(sys.executable, meson, 'compile', '-C', target, '-j', '4', env=env)
        run(sys.executable, meson, 'install', '-C', target, '--no-rebuild', env=env)
        marker.write_text(signature)
        return target
    setup('freetype', ['-Dzlib=disabled','-Dbzip2=disabled','-Dpng=disabled','-Dharfbuzz=disabled','-Dbrotli=disabled'])
    dependency('freetype2', '26.2.20', '-lfreetype', 'include/freetype2')
    setup('fribidi', ['-Ddocs=false','-Dbin=false','-Dtests=false'])
    dependency('fribidi', '1.0.16', '-lfribidi', 'include/fribidi', '-DFRIBIDI_LIB_STATIC')
    setup('harfbuzz', ['-Dtests=disabled','-Ddocs=disabled','-Dutilities=disabled','-Dglib=disabled','-Dgobject=disabled','-Dcairo=disabled','-Dicu=disabled','-Dfreetype=enabled'])
    dependency('harfbuzz', '9.0.0', '-lharfbuzz -lfreetype', 'include/harfbuzz', '-DHB_STATIC')
    setup('libass', ['-Dtest=false','-Dfontconfig=disabled','-Ddirectwrite=enabled','-Dasm=disabled'])
    dependency('libass', '0.17.3', '-lass -lharfbuzz -lfribidi -lfreetype -ldwrite -lusp10 -lgdi32')
    setup('libplacebo', ['-Ddemos=false','-Dvulkan=disabled','-Dopengl=disabled','-Dd3d11=disabled','-Dglslang=disabled','-Dshaderc=disabled','-Dlcms=disabled','-Dlibdovi=disabled','-Dxxhash=disabled'])
    dependency('libplacebo', '6.338.2', '-lplacebo -lshlwapi -ldbghelp', 'include', '-DPL_STATIC')
    built = setup('mpv', ['-Dgpl=false','-Dlibmpv=true','-Dcplayer=false','-Dbuild-date=false','-Dgl=disabled','-Dvulkan=disabled','-Dx11=disabled','-Dwayland=disabled','-Dmanpage-build=disabled','-Dlua=disabled','-Ddefault_library=shared'])
    library = next(built.rglob('libmpv-2.dll'))
    output = args.output.resolve() / 'windows-x64'
    shutil.copyfile(library, output / 'libmpv-2.dll')
    (output / 'mpv-source-provenance.json').write_text(json.dumps([{'name': name, 'url': url, 'archive': archive} for name,url,archive,_ in specifications], indent=2))
    licenses = output / 'licenses'
    for name, source in sources.items():
        for candidate in ['COPYING','LICENSE','LICENSE.LGPL','COPYING.LGPL','docs/FTL.TXT','COPYING.LGPLv2.1','COPYING.MIT']:
            path = source / candidate
            if path.is_file():
                shutil.copyfile(path, licenses / (name + '-' + path.name))
    print('Windows libmpv built from pinned sources')


if __name__ == '__main__':
    main()
