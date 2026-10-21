$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
if (!$python) { $python = Join-Path $projectRoot 'build/native-cache/python-3.13.7/python.exe' }
if (!(Test-Path -LiteralPath $python)) { throw 'Run prepareNative first, or provide Python 3.13' }
& $python (Join-Path $PSScriptRoot 'source_bundle.py') $projectRoot
if ($LASTEXITCODE) { throw 'Native source bundle failed' }
