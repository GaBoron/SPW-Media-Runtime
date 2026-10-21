"""Minimal pkg-config reader for the explicitly declared cross-build dependencies; unknown dependencies fail."""
import os
from pathlib import Path
import re
import sys

args = sys.argv[1:]
if '--version' in args:
    print('1.9.0'); sys.exit(0)
name = next((item for item in args if not item.startswith('-')), '')
paths = [Path(path) for path in os.environ.get('PKG_CONFIG_PATH', '').split(os.pathsep) if path]
source = next((path / (name + '.pc') for path in paths if (path / (name + '.pc')).is_file()), None)
if source is None:
    sys.exit(1)
variables = {}
fields = {}
for line in source.read_text().splitlines():
    if '=' in line and (':' not in line or line.index('=') < line.index(':')):
        key, value = line.split('=', 1); variables[key] = value
    elif ':' in line:
        key, value = line.split(':', 1); fields[key] = value.strip()
def expand(value):
    for _ in range(8):
        value = re.sub(r'\$\{([^}]+)\}', lambda match: variables.get(match[1], ''), value)
    return value
if '--modversion' in args:
    print(fields.get('Version', '0'))
elif '--cflags' in args:
    print(expand(fields.get('Cflags', '')))
elif '--libs' in args or '--libs-only-L' in args or '--libs-only-l' in args:
    print(expand(fields.get('Libs', '')))
elif any(value.startswith('--variable=') for value in args):
    key = next(value.split('=', 1)[1] for value in args if value.startswith('--variable='))
    print(expand(variables.get(key, '')))
