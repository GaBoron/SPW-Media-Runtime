function Find-BuildPython([string]$CacheRoot) {
    $python = Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
    if ($python) { return $python }
    $pythonRoot = Join-Path $CacheRoot 'python-3.13.7'
    $pythonArchive = Join-Path $CacheRoot 'python-3.13.7-embed-amd64.zip'
    if (!(Test-Path -LiteralPath $pythonArchive)) { Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.13.7/python-3.13.7-embed-amd64.zip' -OutFile $pythonArchive }
    if (!(Test-Path -LiteralPath (Join-Path $pythonRoot 'python.exe'))) {
        New-Item -ItemType Directory -Force -Path $pythonRoot | Out-Null
        & tar.exe -xf $pythonArchive -C $pythonRoot
        if ($LASTEXITCODE) { throw 'Portable Python extraction failed' }
    }
    return (Join-Path $pythonRoot 'python.exe')
}
