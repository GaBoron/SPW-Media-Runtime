"""Portable Windows -> glibc 2.35 cross build. Sources and sysroot are cached, never installed system-wide."""
from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import urllib.error
import zipfile


def python_dependency(name: str, version: str, cache: Path) -> None:
    root = cache / 'python-packages'
    root.mkdir(exist_ok=True)
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    if (root / ('.' + name)).exists():
        return
    if name == 'MarkupSafe':
        source = extract(download(f'https://github.com/pallets/markupsafe/archive/refs/tags/{version}.tar.gz', cache / f'markupsafe-{version}.tar.gz'), cache / f'markupsafe-{version}')
        package = next(source.glob('*/src/markupsafe'))
        shutil.copytree(package, root / 'markupsafe', dirs_exist_ok=True)
        (root / ('.' + name)).touch()
        return
    if name == 'Mako':
        source = extract(download('https://github.com/sqlalchemy/mako/archive/refs/tags/rel_' + version.replace('.', '_') + '.tar.gz', cache / f'mako-{version}.tar.gz'), cache / f'mako-{version}')
        package = next(source.glob('*/mako'))
        shutil.copytree(package, root / 'mako', dirs_exist_ok=True)
        (root / ('.' + name)).touch()
        return
    with urllib.request.urlopen(f'https://pypi.org/pypi/{name}/{version}/json') as response:
        metadata = json.load(response)
    tag = f'cp{sys.version_info.major}{sys.version_info.minor}'
    wheel = next(item for item in metadata['urls'] if item['filename'].endswith('.whl') and ('py3-none-any' in item['filename'] or (f'-{tag}-{tag}-win_amd64' in item['filename'])))
    archive = download(wheel['url'], cache / wheel['filename'])
    with zipfile.ZipFile(archive) as source:
        source.extractall(root)
    (root / ('.' + name)).touch()


def download(url: str, target: Path) -> Path:
    if not target.exists():
        print(f"Downloading {target.name}", flush=True)
        target.parent.mkdir(parents=True, exist_ok=True)
        for attempt in range(3):
            try:
                with urllib.request.urlopen(url, timeout=60) as response, target.with_suffix(target.suffix + '.partial').open('wb') as output:
                    shutil.copyfileobj(response, output)
                break
            except urllib.error.URLError:
                if attempt == 2:
                    if os.name != 'nt':
                        raise
                    subprocess.run(['pwsh', '-NoProfile', '-File', str(Path(__file__).parent.parent / 'download.ps1'), '-Url', url, '-Destination', str(target.with_suffix(target.suffix + '.partial'))], check=True)
        target.with_suffix(target.suffix + '.partial').rename(target)
    return target


def extract(archive: Path, root: Path) -> Path:
    if (root / '.complete').exists():
        return root
    root.mkdir(parents=True, exist_ok=True)
    if zipfile.is_zipfile(archive):
        with zipfile.ZipFile(archive) as source:
            source.extractall(root)
    else:
        with tarfile.open(archive) as source:
            # Materialize symlinks as regular copies, without requiring Windows developer mode.
            members = source.getmembers()
            for member in members:
                path = (root / member.name).resolve()
                if not path.is_relative_to(root.resolve()):
                    raise RuntimeError('Archive path escapes extraction directory')
                if member.isfile():
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with source.extractfile(member) as value, path.open('wb') as output:
                        shutil.copyfileobj(value, output)
            for member in members:
                if member.issym() or member.islnk():
                    path = root / member.name
                    link = root / member.linkname if member.islnk() else path.parent / member.linkname
                    if link.is_file() and link.resolve().is_relative_to(root.resolve()):
                        path.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(link, path)
    (root / '.complete').touch()
    return root


def unpack_deb(archive: Path, root: Path) -> list[tuple[Path, Path]]:
    links = []
    with archive.open('rb') as stream:
        if stream.read(8) != b'!<arch>\n':
            raise RuntimeError('Invalid Debian archive')
        while header := stream.read(60):
            name = header[:16].decode().strip().rstrip('/')
            size = int(header[48:58])
            data = stream.read(size)
            if size % 2:
                stream.read(1)
            if name.startswith('data.tar'):
                if name.endswith('.zst'):
                    import zstandard
                    data = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(data)).read()
                with tarfile.open(fileobj=io.BytesIO(data)) as payload:
                    members = payload.getmembers()
                    for member in members:
                        path = (root / member.name).resolve()
                        if not path.is_relative_to(root.resolve()):
                            raise RuntimeError('Invalid sysroot path')
                        if member.isfile():
                            if path.is_file():
                                continue
                            path.parent.mkdir(parents=True, exist_ok=True)
                            with payload.extractfile(member) as value, path.open('wb') as output:
                                shutil.copyfileobj(value, output)
                    for member in members:
                        if member.issym():
                            path = root / member.name
                            target = root / member.linkname.lstrip('/') if member.linkname.startswith('/') else path.parent / member.linkname
                            if target.resolve().is_relative_to(root.resolve()):
                                links.append((path, target))
                return links
    raise RuntimeError('Missing Debian data payload')


def run(*arguments: str | Path, env: dict[str, str]) -> None:
    subprocess.run([str(value) for value in arguments], env=env, check=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    cache = options.cache.resolve() / 'linux-build'
    cache.mkdir(parents=True, exist_ok=True)
    python_dependency('zstandard', '0.25.0', cache)
    python_dependency('MarkupSafe', '3.0.3', cache)
    python_dependency('Mako', '1.3.10', cache)
    output = options.output.resolve() / 'linux-x64'
    output.mkdir(parents=True, exist_ok=True)
    zig_root = extract(download('https://ziglang.org/download/0.15.2/zig-x86_64-windows-0.15.2.zip', cache / 'zig-0.15.2.zip'), cache / 'zig') / 'zig-x86_64-windows-0.15.2'
    ninja = extract(download('https://github.com/ninja-build/ninja/releases/download/v1.13.1/ninja-win.zip', cache / 'ninja-1.13.1.zip'), cache / 'ninja')
    meson = extract(download('https://github.com/mesonbuild/meson/releases/download/1.9.2/meson-1.9.2.tar.gz', cache / 'meson-1.9.2.tar.gz'), cache / 'meson') / 'meson-1.9.2' / 'meson.py'
    ffmpeg_archive = download('https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-10-20-13-08/ffmpeg-n8.1.3-18-g46d899d7a0-linux64-lgpl-shared-8.1.tar.xz', cache / 'ffmpeg-8.1.3.tar.xz')
    ffmpeg = extract(ffmpeg_archive, cache / 'ffmpeg') / 'ffmpeg-n8.1.3-18-g46d899d7a0-linux64-lgpl-shared-8.1'
    mpv = extract(download('https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz', cache / 'mpv-0.41.0.tar.gz'), cache / 'mpv') / 'mpv-0.41.0'
    placebo = extract(download('https://github.com/haasn/libplacebo/archive/refs/tags/v6.338.2.tar.gz', cache / 'libplacebo-6.338.2.tar.gz'), cache / 'placebo') / 'libplacebo-6.338.2'
    vulkan = extract(download('https://github.com/KhronosGroup/Vulkan-Headers/archive/refs/tags/v1.3.296.tar.gz', cache / 'vulkan-headers-1.3.296.tar.gz'), cache / 'vulkan-headers') / 'Vulkan-Headers-1.3.296' / 'include'

    sysroot = cache / 'sysroot'
    sysroot.mkdir(exist_ok=True)
    packages: dict[str, dict[str, str]] = {}
    for section in ('main', 'universe'):
        index = download(f'https://archive.ubuntu.com/ubuntu/dists/jammy/{section}/binary-amd64/Packages.gz', cache / f'jammy-{section}.gz')
        for paragraph in gzip.decompress(index.read_bytes()).decode().split('\n\n'):
            fields = dict(line.split(': ', 1) for line in paragraph.splitlines() if ': ' in line and not line.startswith(' '))
            if 'Package' in fields:
                packages[fields['Package']] = fields
    required = ['libass-dev', 'libass9', 'libasound2-dev', 'libasound2', 'libfontconfig1', 'libfreetype6', 'libfribidi0', 'libharfbuzz0b', 'libpng16-16', 'libbrotli1', 'libgraphite2-3', 'libglib2.0-0', 'libpcre3', 'libexpat1', 'libuuid1', 'libbz2-1.0', 'zlib1g', 'libffi8', 'libgcc-s1']
    provenance = []
    sysroot_links = []
    for name in required:
        package = packages[name]
        filename = package['Filename']
        archive = download('https://archive.ubuntu.com/ubuntu/' + filename, cache / Path(filename).name)
        stamp = sysroot / ('.' + name)
        sysroot_links.extend(unpack_deb(archive, sysroot))
        stamp.touch()
        provenance.append({'package': name, 'version': package['Version'], 'source': package.get('Source', name), 'url': 'https://archive.ubuntu.com/ubuntu/' + filename})
    for _ in range(4):
        for path, target in sysroot_links:
            if not path.exists() and target.is_file():
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(target, path)

    tools = cache / 'tools'
    tools.mkdir(exist_ok=True)
    for name, command in [('cc', 'cc'), ('cxx', 'c++'), ('ar', 'ar'), ('strip', 'objcopy')]:
        headers = f' -I"{vulkan}"' if command in ('cc', 'c++') else ''
        (tools / f'{name}.cmd').write_text(f'@"{zig_root / "zig.exe"}" {command}{headers} %*\n')
    (tools / 'python3.cmd').write_text(f'@"{sys.executable}" %*\n')
    prefix = cache / 'prefix'
    prefix.mkdir(exist_ok=True)
    pc = cache / 'pkgconfig'
    pc.mkdir(exist_ok=True)
    for name, version, library, includes in [
        ('libavcodec', '62.0.0', 'avcodec', ffmpeg / 'include'), ('libavfilter', '11.0.0', 'avfilter', ffmpeg / 'include'),
        ('libavformat', '62.0.0', 'avformat', ffmpeg / 'include'), ('libavutil', '60.0.0', 'avutil', ffmpeg / 'include'),
        ('libswresample', '6.0.0', 'swresample', ffmpeg / 'include'), ('libswscale', '9.0.0', 'swscale', ffmpeg / 'include'),
        ('libass', '0.15.2', 'ass', sysroot / 'usr/include'), ('alsa', '1.2.6', 'asound', sysroot / 'usr/include')]:
        libdir = ffmpeg / 'lib' if name.startswith('libav') or name.startswith('libsw') else sysroot / 'usr/lib/x86_64-linux-gnu'
        (pc / f'{name}.pc').write_text(f'Name: {name}\nDescription: pinned shared {name}\nVersion: {version}\nLibs: -L"{libdir.as_posix()}" -l{library}\nCflags: -I"{includes.as_posix()}"\n')
    # Meson uses this small protocol implementation instead of a system-installed pkg-config.
    pkg = tools / 'pkg-config.cmd'
    pkg.write_text(f'@"{sys.executable}" "{Path(__file__).with_name("pkgconfig.py")}" %*\n')
    cross = cache / 'cross.ini'
    cross.write_text(f'''[binaries]
c = ['{(tools / 'cc.cmd').as_posix()}']
cpp = ['{(tools / 'cxx.cmd').as_posix()}']
ar = ['{(tools / 'ar.cmd').as_posix()}']
strip = ['{(tools / 'strip.cmd').as_posix()}']
pkg-config = ['{pkg.as_posix()}']
[host_machine]
system = 'linux'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'
[properties]
needs_exe_wrapper = true
[built-in options]
c_args = ['-target', 'x86_64-linux-gnu.2.35']
cpp_args = ['-target', 'x86_64-linux-gnu.2.35']
c_link_args = ['-target', 'x86_64-linux-gnu.2.35', '-Wl,-rpath,$ORIGIN', '-lc++']
cpp_link_args = ['-target', 'x86_64-linux-gnu.2.35']
''')
    env = os.environ.copy()
    env['PATH'] = str(tools) + os.pathsep + str(ninja) + os.pathsep + env['PATH']
    env['PKG_CONFIG_PATH'] = str(pc) + os.pathsep + str(prefix / 'lib/pkgconfig')
    env['ZIG_GLOBAL_CACHE_DIR'] = str(cache / 'zig-cache')
    env['ZIG_LOCAL_CACHE_DIR'] = str(cache / 'zig-local-cache')
    env['PYTHONPATH'] = str(cache / 'python-packages')
    jni = cache / 'jni-25'
    jni.mkdir(exist_ok=True)
    for platform, name in [('share', 'jni.h'), ('unix', 'jni_md.h')]:
        source = download(f'https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/{platform}/native/include/{name}', jni / name)
    launcher = output / 'spw-jvm-worker'
    run(zig_root / 'zig.exe', 'c++', '-target', 'x86_64-linux-gnu.2.35', '-std=c++17', '-O2', '-I', jni, Path(__file__).parent.parent / 'jvm/launcher.cpp', '-ldl', '-o', launcher, env=env)
    def setup(name: str, source: Path, flags: list[str]) -> Path:
        if name == 'mpv':
            flags = flags + ['-Dc_link_args=' + json.dumps(['-target', 'x86_64-linux-gnu.2.35', '-Wl,-rpath,$ORIGIN', '-lc++'])]
        target = cache / (name + '-build')
        signature = json.dumps({'flags': flags, 'cross': cross.read_text()})
        completed = target / 'completed-inputs.json'
        artifact = target / ('libmpv.so.2.5.0' if name == 'mpv' else 'src/libplacebo.a')
        if completed.exists() and completed.read_text() == signature and artifact.is_file():
            return target
        if not (target / 'build.ninja').exists():
            run(sys.executable, meson, 'setup', target, source, '--cross-file', cross, '--prefix', prefix, '--libdir', 'lib', '--buildtype', 'release', '--wrap-mode', 'nodownload', *flags, env=env)
        else:
            marker = target / 'requested-options.json'
            value = json.dumps(flags)
            if not marker.exists() or marker.read_text() != value:
                run(sys.executable, meson, 'configure', target, *flags, env=env)
                marker.write_text(value)
            run(sys.executable, meson, 'setup', target, source, '--reconfigure', '--clearcache', env=env)
        config = target / 'config.h'
        if name == 'mpv' and config.exists():
            # Upstream embeds Windows backslashes unescaped in CONFIGURATION; retain the actual options without machine paths.
            contents = config.read_text()
            config.write_text(re.sub(r'^#define CONFIGURATION .*$', lambda _: '#define CONFIGURATION ' + json.dumps('glibc2.35 portable-cross ' + ' '.join(flags)), contents, flags=re.MULTILINE))
        run(sys.executable, meson, 'compile', '-C', target, '-j', '4', env=env)
        run(sys.executable, meson, 'install', '-C', target, '--no-rebuild', env=env)
        completed.write_text(signature)
        return target
    setup('placebo', placebo, ['-Ddefault_library=static', '-Ddemos=false', '-Dvulkan=disabled', '-Dopengl=disabled', '-Dd3d11=disabled', '-Dglslang=disabled', '-Dshaderc=disabled', '-Dlcms=disabled', '-Dlibdovi=disabled', '-Dxxhash=disabled'])
    built = setup('mpv', mpv, ['-Dgpl=false', '-Dlibmpv=true', '-Dcplayer=false', '-Dbuild-date=false', '-Dgl=disabled', '-Dvulkan=disabled', '-Dx11=disabled', '-Dwayland=disabled', '-Dalsa=enabled', '-Dmanpage-build=disabled', '-Dlua=disabled'])
    for name in ('ffmpeg', 'ffprobe'):
        shutil.copyfile(ffmpeg / 'bin' / name, output / name)
    for root in [ffmpeg / 'lib', sysroot / 'usr/lib/x86_64-linux-gnu', sysroot / 'lib/x86_64-linux-gnu']:
        for library in root.glob('*.so*'):
            if library.is_file():
                shutil.copyfile(library, output / library.name)
    shutil.copyfile(built / 'libmpv.so.2.5.0', output / 'libmpv.so.2')
    from elf import inspect, relocate
    relocate(output / 'libmpv.so.2')
    system = {'libc.so.6', 'libm.so.6', 'libmvec.so.1', 'libresolv.so.2', 'libpthread.so.0', 'librt.so.1', 'libdl.so.2', 'ld-linux-x86-64.so.2'}
    selected = set()
    pending = ['ffmpeg', 'ffprobe', 'libmpv.so.2', 'spw-jvm-worker']
    reports = []
    while pending:
        name = pending.pop()
        if name in selected or name in system:
            continue
        path = output / name
        if not path.is_file():
            raise RuntimeError(f'Missing Linux dependency: {name}')
        report = inspect(path)
        for version in report['glibc']:
            if version != 'GLIBC_PRIVATE' and tuple(map(int, version.removeprefix('GLIBC_').split('.'))) > (2, 35):
                raise RuntimeError(f'{name} exceeds the glibc 2.35 baseline: {version}')
        if name == 'libmpv.so.2' and any(symbol.startswith('_ZNSt3__1') for symbol in report['undefined']):
            raise RuntimeError('The C++ runtime was not linked into libmpv')
        reports.append({key: value for key, value in report.items() if key != 'undefined'})
        selected.add(name)
        pending.extend(report['needed'])
    package_files = ['assets.properties', 'asset-manifest.properties', 'sysroot-provenance.json', 'elf-dependencies.json', 'packaged-files.txt']
    for name in sorted(selected):
        directory = output / ('bin' if name in ('ffmpeg', 'ffprobe', 'spw-jvm-worker') else 'lib')
        directory.mkdir(exist_ok=True)
        shutil.copyfile(output / name, directory / name)
        package_files.append(directory.name + '/' + name)
    licenses = output / 'licenses'
    licenses.mkdir(exist_ok=True)
    shutil.copyfile(ffmpeg / 'LICENSE.txt', licenses / 'FFmpeg-LGPL.txt')
    for source in (sysroot / 'usr/share/doc').glob('*/copyright'):
        shutil.copyfile(source, licenses / (source.parent.name + '.copyright'))
    for source in licenses.iterdir():
        package_files.append('licenses/' + source.name)
    (output / 'packaged-files.txt').write_text('\n'.join(package_files))
    (output / 'elf-dependencies.json').write_text(json.dumps(reports, indent=2))
    version = next(line.split('=', 1)[1] for line in (Path(__file__).parent.parent.parent / 'gradle.properties').read_text().splitlines() if line.startswith('version='))
    (output / 'assets.properties').write_text('ffmpeg.path=bin/ffmpeg\nffmpeg.version=8.1.3-18-g46d899d7a0\nffprobe.path=bin/ffprobe\nffprobe.version=8.1.3-18-g46d899d7a0\nmpv.path=lib/libmpv.so.2\nmpv.version=0.41.0\nworker.path=bin/spw-jvm-worker\nworker.version=' + version + '\n')
    (output / 'sysroot-provenance.json').write_text(json.dumps(provenance, indent=2))


if __name__ == '__main__':
    main()
