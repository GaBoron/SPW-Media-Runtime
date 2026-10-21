param([switch]$WindowsOnly, [switch]$LinuxOnly)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$cacheRoot = Join-Path $projectRoot 'build/native-cache'
$outputRoot = Join-Path $projectRoot 'build/native'
$projectVersion = ((Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') | Where-Object { $_ -match '^version=' }) -split '=',2)[1]
New-Item -ItemType Directory -Force -Path $cacheRoot,$outputRoot | Out-Null

function Get-Asset([string]$Url, [string]$Name) {
    $target = Join-Path $cacheRoot $Name
    if (!(Test-Path -LiteralPath $target)) {
        Write-Host "Downloading $Name"
        Invoke-WebRequest -Uri $Url -OutFile "$target.partial"
        Move-Item -LiteralPath "$target.partial" -Destination $target
    }
    return $target
}
function Expand-Asset([string]$Archive, [string]$Directory) {
    if (Test-Path -LiteralPath (Join-Path $Directory '.complete')) { return }
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    & tar.exe -xf $Archive -C $Directory
    if ($LASTEXITCODE) { throw "Extraction failed: $Archive" }
    New-Item -ItemType File -Force -Path (Join-Path $Directory '.complete') | Out-Null
}

if (!$LinuxOnly) {
    $output = Join-Path $outputRoot 'windows-x64'
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $ffmpeg = Get-Asset 'https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-10-20-13-08/ffmpeg-n8.1.3-18-g46d899d7a0-win64-lgpl-shared-8.1.zip' 'windows-ffmpeg-8.1.3.zip'
    $expanded = Join-Path $cacheRoot 'windows-media'
    Expand-Asset $ffmpeg (Join-Path $expanded 'ffmpeg-8.1.3')
    foreach ($name in @('ffmpeg.exe','ffprobe.exe')) {
        $searchRoot = Join-Path $expanded 'ffmpeg-8.1.3'
        $source = Get-ChildItem -LiteralPath $searchRoot -Filter $name -Recurse | Select-Object -First 1
        if (!$source) { throw "Asset absent: $name" }
        Copy-Item -LiteralPath $source.FullName -Destination $output -Force
    }
    Get-ChildItem -LiteralPath (Join-Path $expanded 'ffmpeg-8.1.3') -Filter '*.dll' -Recurse | ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $output -Force }
    $sdkVersion = '1.0.3537.50'
    $sdkArchive = Get-Asset "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$sdkVersion/microsoft.web.webview2.$sdkVersion.nupkg" "webview2-$sdkVersion.zip"
    $sdk = Join-Path $cacheRoot "webview2-$sdkVersion"
    Expand-Asset $sdkArchive $sdk
    Copy-Item -LiteralPath (Join-Path $sdk 'build/native/x64/WebView2Loader.dll') -Destination $output -Force
    $licenses = Join-Path $output 'licenses'
    New-Item -ItemType Directory -Force -Path $licenses | Out-Null
    Copy-Item -LiteralPath (Join-Path $sdk 'LICENSE.txt') -Destination (Join-Path $licenses 'WebView2.txt') -Force
    $ffmpegLicense = Get-ChildItem -LiteralPath (Join-Path $expanded 'ffmpeg-8.1.3') -Filter LICENSE.txt -Recurse | Select-Object -First 1
    Copy-Item -LiteralPath $ffmpegLicense.FullName -Destination (Join-Path $licenses 'FFmpeg-LGPLv3.txt') -Force
    $header = Get-Asset 'https://raw.githubusercontent.com/nlohmann/json/v3.12.0/single_include/nlohmann/json.hpp' 'json-3.12.0.hpp'
    $include = Join-Path $cacheRoot 'include/nlohmann'
    New-Item -ItemType Directory -Force -Path $include | Out-Null
    Copy-Item -LiteralPath $header -Destination (Join-Path $include 'json.hpp') -Force
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
    $vs = & $vswhere -latest -products '*' -property installationPath
    if (!$vs) { throw 'MSVC is required for Windows native helpers' }
    $msvc = Get-ChildItem -LiteralPath (Join-Path $vs 'VC/Tools/MSVC') -Directory | Sort-Object Name -Descending | Select-Object -First 1
    $crtInclude = Join-Path $msvc.FullName 'include'
    $crtLib = Join-Path $msvc.FullName 'lib/x64'
    if (!(Test-Path -LiteralPath (Join-Path $crtInclude 'excpt.h'))) {
        $headers = Get-Asset 'https://download.visualstudio.microsoft.com/download/pr/a62262cf-b9f6-4770-9454-7372ad8fcec0/e6d9cc5f0750cbc40a6b2468991c57ed0044fa2e242295a6d51bc02d72502b05/Microsoft.VC.14.51.CRT.Headers.base.vsix' 'Microsoft.VC.14.51.CRT.Headers.base.vsix'
        $libraries = Get-Asset 'https://download.visualstudio.microsoft.com/download/pr/16ac3a9f-21a9-4b80-a1cb-097bd79ea455/b86f502f4092c11af0c99de3e1b535da17d0116af07e8b18dfec58e0b514485c/Microsoft.VC.14.51.CRT.x64.Desktop.base.vsix' 'Microsoft.VC.14.51.CRT.x64.Desktop.base.vsix'
        $headersRoot = Join-Path $cacheRoot 'Microsoft.VC.14.51.CRT.Headers.base'
        $librariesRoot = Join-Path $cacheRoot 'Microsoft.VC.14.51.CRT.x64.Desktop.base'
        Expand-Asset $headers $headersRoot; Expand-Asset $libraries $librariesRoot
        $crtInclude = Join-Path $headersRoot 'Contents/VC/Tools/MSVC/14.51.36231/include'
        $crtLib = Join-Path $librariesRoot 'Contents/VC/Tools/MSVC/14.51.36231/lib/x64'
    }
    $kits = Join-Path ${env:ProgramFiles(x86)} 'Windows Kits/10'
    $kitVersion = (Get-ChildItem -LiteralPath (Join-Path $kits 'Include') -Directory | Sort-Object Name -Descending | Select-Object -First 1).Name
    $env:INCLUDE = @($crtInclude,(Join-Path $kits "Include/$kitVersion/ucrt"),(Join-Path $kits "Include/$kitVersion/shared"),(Join-Path $kits "Include/$kitVersion/um"),(Join-Path $kits "Include/$kitVersion/winrt")) -join ';'
    $env:LIB = @($crtLib,(Join-Path $msvc.FullName 'lib/onecore/x64'),(Join-Path $kits "Lib/$kitVersion/ucrt/x64"),(Join-Path $kits "Lib/$kitVersion/um/x64")) -join ';'
    $compiler = Join-Path $msvc.FullName 'bin/Hostx64/x64/cl.exe'
    $objects = Join-Path $cacheRoot 'windows-objects'
    New-Item -ItemType Directory -Force -Path $objects | Out-Null
    & $compiler /nologo /std:c++17 /EHsc /O2 /MT /utf-8 "/I$(Join-Path $cacheRoot 'include')" "/I$(Join-Path $sdk 'build/native/include')" "/Fo$objects/" (Join-Path $PSScriptRoot 'windows/main.cpp') (Join-Path $PSScriptRoot 'windows/web.cpp') (Join-Path $PSScriptRoot 'windows/capture.cpp') "/Fe$(Join-Path $output 'spw-platform.exe')" /link (Join-Path $sdk 'build/native/x64/WebView2Loader.dll.lib') ole32.lib user32.lib uuid.lib /SUBSYSTEM:WINDOWS /ENTRY:mainCRTStartup
    if ($LASTEXITCODE) { throw 'Windows native build failed' }
    & $compiler /nologo /std:c++17 /EHsc /O2 /MT /utf-8 "/Fo$objects/" (Join-Path $PSScriptRoot 'windows/recycle.cpp') "/Fe$(Join-Path $output 'spw-recycle.exe')" /link ole32.lib shell32.lib uuid.lib /SUBSYSTEM:WINDOWS /ENTRY:wmainCRTStartup
    if ($LASTEXITCODE) { throw 'Windows silent recycling helper build failed' }
    $jni = Join-Path $cacheRoot 'jni-25'
    New-Item -ItemType Directory -Force -Path $jni | Out-Null
    foreach ($entry in @(@('share','jni.h'), @('windows','jni_md.h'))) {
        $source = Get-Asset "https://raw.githubusercontent.com/openjdk/jdk/jdk-25%2B36/src/java.base/$($entry[0])/native/include/$($entry[1])" "jdk25-$($entry[0])-$($entry[1])"
        Copy-Item -LiteralPath $source -Destination (Join-Path $jni $entry[1]) -Force
    }
    & $compiler /nologo /std:c++17 /EHsc /O2 /MT /utf-8 "/I$jni" "/Fo$objects/" (Join-Path $PSScriptRoot 'jvm/launcher.cpp') "/Fe$(Join-Path $output 'spw-jvm-worker.exe')" /link /SUBSYSTEM:WINDOWS /ENTRY:wmainCRTStartup
    if ($LASTEXITCODE) { throw 'JVM worker bootstrap build failed' }
    . (Join-Path $PSScriptRoot 'python.ps1')
    $python = Find-BuildPython $cacheRoot
    & $python (Join-Path $PSScriptRoot 'windows/build_mpv.py') --cache $cacheRoot --output $outputRoot
    if ($LASTEXITCODE) { throw 'Windows libmpv source build failed' }
    @"
ffmpeg.path=ffmpeg.exe
ffmpeg.version=8.1.3-18-g46d899d7a0
ffprobe.path=ffprobe.exe
ffprobe.version=8.1.3-18-g46d899d7a0
mpv.path=libmpv-2.dll
mpv.version=0.41.0
platform.path=spw-platform.exe
platform.version=$projectVersion
webview2.path=WebView2Loader.dll
webview2.version=$sdkVersion
recycle.path=spw-recycle.exe
recycle.version=$projectVersion
worker.path=spw-jvm-worker.exe
worker.version=$projectVersion
"@ | Set-Content -LiteralPath (Join-Path $output 'assets.properties') -Encoding utf8
}
if (!$WindowsOnly) { & (Join-Path $PSScriptRoot 'linux/prepare.ps1') -CacheRoot $cacheRoot -OutputRoot $outputRoot; if (!$?) { throw 'Linux native preparation failed' } }
