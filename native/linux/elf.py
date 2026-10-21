"""ELF64 packaging inspection; does not execute target binaries on the build machine."""
from pathlib import Path
import struct


def inspect(path: Path) -> dict:
    data = path.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError(f'Not a little-endian ELF64 binary: {path}')
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    section_offset, section_size, count = header[6], header[11], header[12]
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, section_offset + index * section_size) for index in range(count)]
    def text(table, offset):
        start = table[4] + offset
        return data[start:data.index(b'\0', start)].decode()
    needed = []
    runpaths = []
    undefined = []
    glibc = set()
    for section in sections:
        if section[1] == 6:
            strings = sections[section[6]]
            for offset in range(section[4], section[4] + section[5], 16):
                tag, value = struct.unpack_from('<qQ', data, offset)
                if tag == 1:
                    needed.append(text(strings, value))
                elif tag in (15, 29):
                    runpaths.append(text(strings, value))
        if section[1] == 11:
            strings = sections[section[6]]
            for offset in range(section[4], section[4] + section[5], 24):
                name, info, _, index, _, _ = struct.unpack_from('<IBBHQQ', data, offset)
                if index == 0 and name and (info >> 4) == 1:
                    undefined.append(text(strings, name))
        if section[1] == 0x6ffffffe:
            strings = sections[section[6]]
            offset = section[4]
            while offset < section[4] + section[5]:
                _, aux_count, _, auxiliary, following = struct.unpack_from('<HHIII', data, offset)
                aux = offset + auxiliary
                for _ in range(aux_count):
                    _, _, _, name, next_aux = struct.unpack_from('<IHHII', data, aux)
                    version = text(strings, name)
                    if version.startswith('GLIBC_'):
                        glibc.add(version)
                    if not next_aux:
                        break
                    aux += next_aux
                if not following:
                    break
                offset += following
    return {'file': path.name, 'needed': needed, 'runpaths': runpaths, 'undefined': undefined, 'glibc': sorted(glibc)}


def relocate(path: Path, value: str = '$ORIGIN') -> None:
    """Replace build-machine RPATH strings without moving ELF sections or changing the library ABI."""
    data = bytearray(path.read_bytes())
    header = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, header[6] + index * header[11]) for index in range(header[12])]
    for section in sections:
        if section[1] != 6:
            continue
        strings = sections[section[6]]
        for offset in range(section[4], section[4] + section[5], 16):
            tag, index = struct.unpack_from('<qQ', data, offset)
            if tag not in (15, 29):
                continue
            start = strings[4] + index
            end = data.index(0, start)
            encoded = value.encode()
            if len(encoded) > end - start:
                raise RuntimeError('ELF RPATH does not have sufficient room for relocation')
            data[start:end] = encoded + b'\0' * (end - start - len(encoded))
    path.write_bytes(data)


if __name__ == '__main__':
    import json
    import sys
    print(json.dumps(inspect(Path(sys.argv[1])), indent=2))
