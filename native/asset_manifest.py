"""Build dependency closures and immutable content identities for packaged native assets."""
import hashlib
import os
from pathlib import Path
import struct
import sys


def pe_imports(path):
    data = path.read_bytes()
    offset = struct.unpack_from('<I', data, 0x3c)[0]
    if data[offset:offset + 4] != b'PE\0\0':
        raise ValueError(f'Invalid PE file: {path.name}')
    count, optional_size = struct.unpack_from('<H', data, offset + 6)[0], struct.unpack_from('<H', data, offset + 20)[0]
    optional = offset + 24
    directory = optional + (112 if struct.unpack_from('<H', data, optional)[0] == 0x20b else 96)
    imports = struct.unpack_from('<I', data, directory + 8)[0]
    sections = []
    for index in range(count):
        entry = optional + optional_size + index * 40
        size, address, raw_size, raw = struct.unpack_from('<IIII', data, entry + 8)
        sections.append((address, max(size, raw_size), raw))
    def position(rva):
        for address, size, raw in sections:
            if address <= rva < address + size:
                return raw + rva - address
        raise ValueError('Import outside PE sections')
    names = []
    if imports:
        cursor = position(imports)
        while any(data[cursor:cursor + 20]):
            name = position(struct.unpack_from('<I', data, cursor + 12)[0])
            names.append(data[name:data.index(0, name)].decode('ascii'))
            cursor += 20
    return names


def generate(root):
    properties = dict(line.split('=', 1) for line in (root / 'assets.properties').read_text(encoding='utf-8-sig').splitlines() if '=' in line)
    paths = {key[:-5]: value for key, value in properties.items() if key.endswith('.path')}
    windows = root.name == 'windows-x64'
    if windows:
        files = {p.name.lower(): p for p in root.iterdir() if p.is_file() and p.suffix.lower() in ('.exe', '.dll')}
        dependencies = pe_imports
    else:
        sys.path.insert(0, str(Path(__file__).parent / 'linux'))
        from elf import inspect
        files = {p.name: p for directory in ('bin', 'lib') for p in (root / directory).iterdir() if p.is_file()}
        dependencies = lambda path: inspect(path)['needed']
    groups = {}
    for name, relative in paths.items():
        pending = [root / relative]
        if name == 'platform':
            pending.append(root / paths['webview2'])
        selected = set()
        while pending:
            file = pending.pop()
            if file in selected:
                continue
            if not file.is_file():
                raise ValueError(f'Missing native asset: {file}')
            selected.add(file)
            for dependency in dependencies(file):
                key = dependency.lower() if windows else dependency
                if key in files:
                    pending.append(files[key])
                elif not windows and dependency not in {'libc.so.6', 'libm.so.6', 'libmvec.so.1', 'libresolv.so.2', 'libpthread.so.0', 'librt.so.1', 'libdl.so.2', 'ld-linux-x86-64.so.2'}:
                    raise ValueError(f'Missing packaged dependency: {dependency}')
                elif windows and not dependency.lower().startswith(('api-ms-', 'ext-ms-')) and not (Path(os.environ['SystemRoot']) / 'System32' / dependency).is_file():
                    raise ValueError(f'Missing Windows dependency: {dependency}')
        groups[name] = sorted(p.relative_to(root).as_posix() for p in selected)
    records = []
    for relative in sorted({p for group in groups.values() for p in group}):
        file = root / relative
        with file.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        records.extend((f'file.{relative}.size={file.stat().st_size}', f'file.{relative}.digest={digest}'))
    records.extend(f'asset.{name}.files={",".join(paths)}' for name, paths in sorted(groups.items()))
    identity = hashlib.sha256('\n'.join(records).encode()).hexdigest()
    (root / 'asset-manifest.properties').write_text(f'format=1\nidentity={identity}\n' + '\n'.join(records) + '\n', encoding='ascii')
    print(f'{root.name}: {len(groups)} asset closures, {len({p for group in groups.values() for p in group})} shared files')


if __name__ == '__main__':
    for platform in ('windows-x64', 'linux-x64'):
        generate(Path(sys.argv[1]) / platform)
