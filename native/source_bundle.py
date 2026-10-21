"""Collect pinned primary sources, upstream build recipes and Ubuntu corresponding source packages."""
import gzip
import json
from pathlib import Path
import shutil
import sys
import zipfile
import os
import subprocess

sys.path.insert(0, str(Path(__file__).parent / 'linux'))
from build import download


def paragraphs(value):
    result = []
    for paragraph in value.split('\n\n'):
        record = {}
        key = ''
        for line in paragraph.splitlines():
            if line.startswith(' ') and key:
                record[key] += '\n' + line.strip()
            elif ':' in line:
                key, field = line.split(':', 1)
                record[key] = field.strip()
        if record:
            result.append(record)
    return result


def main(root):
    cache = root / 'build/native-cache'
    destination = root / 'build/native-sources'
    destination.mkdir(parents=True, exist_ok=True)
    sources = [
        ('jdk25-share-jni.h', 'https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/share/native/include/jni.h'),
        ('jdk25-windows-jni_md.h', 'https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/windows/native/include/jni_md.h'),
        ('jdk25-unix-jni_md.h', 'https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/unix/native/include/jni_md.h'),
        ('ffmpeg-46d899d7a0.tar.gz', 'https://github.com/FFmpeg/FFmpeg/archive/46d899d7a0.tar.gz'),
        ('mpv-0.41.0.tar.gz', 'https://github.com/mpv-player/mpv/archive/refs/tags/v0.41.0.tar.gz'),
        ('libplacebo-6.338.2.tar.gz', 'https://github.com/haasn/libplacebo/archive/refs/tags/v6.338.2.tar.gz'),
        ('ffmpeg-build-recipes.tar.gz', 'https://github.com/BtbN/FFmpeg-Builds/archive/refs/tags/autobuild-2026-10-20-13-08.tar.gz'),
        ('jaudiotagger-3.0.1-sources.jar', 'https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar'),
        ('freetype-2.13.3.tar.gz', 'https://github.com/freetype/freetype/archive/refs/tags/VER-2-13-3.tar.gz'),
        ('fribidi-1.0.16.tar.xz', 'https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz'),
        ('harfbuzz-9.0.0.tar.gz', 'https://github.com/harfbuzz/harfbuzz/archive/refs/tags/9.0.0.tar.gz'),
        ('libass-0.17.3.tar.gz', 'https://github.com/libass/libass/archive/refs/tags/0.17.3.tar.gz'),
    ]
    records = []
    for name, url in sources:
        existing = cache / 'windows-mpv-source-build' / name
        source = existing if existing.is_file() else download(url, cache / 'sources' / name)
        shutil.copyfile(source, destination / name)
        records.append({'archive': name, 'url': url})
    package_file = root / 'build/native/linux-x64/sysroot-provenance.json'
    if package_file.exists():
        packages = json.loads(package_file.read_text())
        required = {item['source'].split(' ')[0] for item in packages}
        indexes = {}
        for section in ('main', 'universe'):
            source = download(f'https://archive.ubuntu.com/ubuntu/dists/jammy/{section}/source/Sources.gz', cache / 'sources' / f'jammy-{section}-sources.gz')
            for record in paragraphs(gzip.decompress(source.read_bytes()).decode()):
                if record.get('Package') in required:
                    indexes[record['Package']] = record
        for name in sorted(required):
            record = indexes[name]
            for entry in record['Files'].strip().splitlines():
                filename = entry.split()[-1]
                url = 'https://archive.ubuntu.com/ubuntu/' + record['Directory'] + '/' + filename
                source = download(url, cache / 'sources' / filename)
                shutil.copyfile(source, destination / filename)
                records.append({'package': name, 'version': record['Version'], 'archive': filename, 'url': url})
    (destination / 'sources.json').write_text(json.dumps(records, indent=2))
    selected = {destination / item['archive'] for item in records}
    selected.add(destination / 'sources.json')
    # The portable compiler statically links these runtimes; preserve their matching sources too.
    zig = cache / 'linux-build/zig/zig-x86_64-windows-0.15.2'
    for directory in ('lib/libcxx', 'lib/libcxxabi', 'lib/libunwind', 'lib/libc/mingw'):
        for path in (zig / directory).rglob('*'):
            if path.is_file():
                target = destination / 'compiler-runtime-sources' / path.relative_to(zig)
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(path, target)
                selected.add(target)
    for path in (root / 'native').rglob('*'):
        if path.is_file() and path.suffix in ('.py', '.ps1', '.cpp', '.hpp'):
            target = destination / 'build-recipes' / path.relative_to(root / 'native')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            selected.add(target)
    version = next(line.split('=', 1)[1] for line in (root / 'gradle.properties').read_text().splitlines() if line.startswith('version='))
    upstream = Path(os.environ.get('SPW_MEDIA_FFMPEG_SOURCES', str(cache / 'ffmpeg-corresponding-source-cache.zip')))
    if not upstream.is_file():
        client = shutil.which('gh')
        if not client:
            raise RuntimeError('Corresponding FFmpeg dependency sources require authenticated gh or SPW_MEDIA_FFMPEG_SOURCES pointing to the saved upstream source-cache ZIP')
        with upstream.open('wb') as stream:
            subprocess.run([client, 'api', 'repos/BtbN/FFmpeg-Builds/actions/artifacts/12263308818/zip'], stdout=stream, check=True)
    with zipfile.ZipFile(upstream) as archive:
        if 'cache.tar.gz' not in archive.namelist():
            raise RuntimeError('Incorrect upstream FFmpeg source-cache archive')
    extra = root / 'build/distributions' / f'spw-media-ffmpeg-dependency-sources-{version}.zip'
    extra.parent.mkdir(parents=True, exist_ok=True)
    if not extra.exists():
        try:
            os.link(upstream, extra)
        except OSError:
            shutil.copyfile(upstream, extra)
    artifact = root / 'build/distributions' / f'spw-media-native-sources-{version}.zip'
    artifact.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(artifact, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(selected):
            if path.is_file():
                archive.write(path, path.relative_to(destination))
    print(f'Native source bundle: {artifact.name}')
    print(f'Corresponding FFmpeg dependency sources: {extra.name}')


if __name__ == '__main__':
    main(Path(sys.argv[1]).resolve())
