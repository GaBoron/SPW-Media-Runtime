$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'python.ps1')
$cacheRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'build/native-cache'
$python = Find-BuildPython $cacheRoot
& $python (Join-Path $PSScriptRoot 'asset_manifest.py') (Join-Path (Split-Path -Parent $PSScriptRoot) 'build/native')
if ($LASTEXITCODE) { throw 'Native dependency manifest generation failed' }
