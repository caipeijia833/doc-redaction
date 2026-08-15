$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$version = '4.13.0'
$archiveHash = 'f0e98c302464d6860777a7015065e11b9b271b5394e6ba92663f0cf1fc303f2c'
$jarHash = '00e2c856933993d948910f77344ac99ce38b0f875af2866a164f8fff82e9fb5f'
$dllHash = '0a3fcf83e381ce5f49e155ff10e5b3a20dfc7c6590dbfb2c6c5f3dd8b684ab6c'
$downloads = Join-Path $projectRoot '.tools\downloads'
$archive = Join-Path $downloads "opencv-$version-windows.exe"
$targetRoot = Join-Path $projectRoot ".tools\opencv-official-$version"
$opencvRoot = Join-Path $targetRoot 'opencv'
$jar = Join-Path $opencvRoot 'build\java\opencv-4130.jar'
$dll = Join-Path $opencvRoot 'build\java\x64\opencv_java4130.dll'

function Assert-Hash([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() -eq $Expected
}

if ((Assert-Hash $jar $jarHash) -and (Assert-Hash $dll $dllHash)) {
    Write-Host "OPENCV_READY=$opencvRoot"
    exit 0
}

New-Item -ItemType Directory -Path $downloads -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    Invoke-WebRequest -Uri "https://github.com/opencv/opencv/releases/download/$version/opencv-$version-windows.exe" `
        -OutFile $archive
}
if (-not (Assert-Hash $archive $archiveHash)) {
    throw 'The official OpenCV archive failed its pinned SHA-256 check.'
}

$sevenZip = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools\ocr-minimal-build\src\vcpkg\downloads\tools') `
    -Recurse -Filter '7z.exe' -File | Select-Object -First 1 -ExpandProperty FullName
if (-not $sevenZip) { throw 'A verified local 7-Zip executable was not found after the OCR toolchain build.' }

$resolvedTools = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '.tools'))
$resolvedTarget = [System.IO.Path]::GetFullPath($targetRoot)
if (-not $resolvedTarget.StartsWith($resolvedTools + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to replace an OpenCV cache outside the project .tools directory.'
}
if (Test-Path -LiteralPath $targetRoot) { Remove-Item -LiteralPath $targetRoot -Recurse -Force }
New-Item -ItemType Directory -Path $targetRoot | Out-Null
& $sevenZip x $archive ("-o" + $targetRoot) `
    'opencv\build\java\opencv-4130.jar' `
    'opencv\build\java\x64\opencv_java4130.dll' `
    'opencv\build\LICENSE' 'opencv\build\etc\licenses\*' `
    'opencv\LICENSE.txt' 'opencv\LICENSE_FFMPEG.txt' -y
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if (-not (Assert-Hash $jar $jarHash) -or -not (Assert-Hash $dll $dllHash)) {
    throw 'Extracted OpenCV Java components failed their pinned SHA-256 checks.'
}
Write-Host "OPENCV_READY=$opencvRoot"
