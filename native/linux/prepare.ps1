param([Parameter(Mandatory)][string]$CacheRoot, [Parameter(Mandatory)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../python.ps1')
$python = Find-BuildPython $CacheRoot
& $python (Join-Path $PSScriptRoot 'build.py') --cache $CacheRoot --output $OutputRoot
if ($LASTEXITCODE) { throw 'Linux cross build failed' }
