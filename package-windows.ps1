$ErrorActionPreference = 'Stop'

$projectRoot = $PSScriptRoot
$javaHome = Join-Path $projectRoot '.tools\jdk-dist\jdk-21.0.12+8'
$jlinkExe = Join-Path $javaHome 'bin\jlink.exe'
$jarExe = Join-Path $javaHome 'bin\jar.exe'
$sourceJar = Join-Path $projectRoot 'app\build\libs\doc-redaction-poc-0.1.0-poc-all.jar'
$sourceSbom = Join-Path $projectRoot 'app\build\reports\cyclonedx-direct\bom.json'
$sourceOfdWorker = Join-Path $projectRoot 'app\build\ofd-worker'
$sourceOcr = Join-Path $projectRoot '.tools\ocr-minimal-build\runtime'
$sourceFfmpegParent = Join-Path $projectRoot '.tools\media-components\ffmpeg-8.1-win64-lgpl-shared'
$sourceFfmpeg = Get-ChildItem -LiteralPath $sourceFfmpegParent -Directory | Select-Object -First 1 -ExpandProperty FullName
$sourceWhisper = Join-Path $projectRoot '.tools\media-components\whisper-1.9.2-win64\Release'
$sourceMediaModels = Join-Path $projectRoot '.tools\media-models'
$sourceWhisperModel = Join-Path $projectRoot '.tools\media-downloads\ggml-base-q5_1.bin'
$sourceMediaManifest = Join-Path $projectRoot 'audit\MEDIA_COMPONENTS.json'
$sourceMediaLicenses = Join-Path $projectRoot 'audit\media-licenses'
$sourceFfmpegArchive = Join-Path $projectRoot '.tools\media-downloads\ffmpeg-n8.1-win64-lgpl-shared.zip'
$sourceWhisperArchive = Join-Path $projectRoot '.tools\media-downloads\whisper-v1.9.2-x64.zip'
$sourceMediaSamples = Join-Path $projectRoot '.tools\media-smoke'
$sourceOpenCvArchive = Join-Path $projectRoot '.tools\downloads\opencv-4.13.0-windows.exe'
$sourceOpenCv = Join-Path $projectRoot '.tools\opencv-official-4.13.0\opencv'
$sourceOpenCvJar = Join-Path $sourceOpenCv 'build\java\opencv-4130.jar'
$sourceOpenCvDll = Join-Path $sourceOpenCv 'build\java\x64\opencv_java4130.dll'
$sourceVlmRuntime = Join-Path $projectRoot '.tools\vlm-runtime'
$sourceVlmModels = Join-Path $projectRoot '.tools\vlm-models'
$sourceVlmManifest = Join-Path $projectRoot 'audit\VLM_COMPONENTS.json'
$sourceVlmLicenses = Join-Path $projectRoot 'audit\vlm-licenses'
$distRoot = Join-Path $projectRoot 'dist'
$packageRoot = Join-Path $distRoot 'doc-redaction-poc-windows-x64'
$archive = Join-Path $distRoot 'doc-redaction-poc-windows-x64.zip'
$stableTime = [DateTime]::SpecifyKind([DateTime]'2026-08-11T00:00:00', [DateTimeKind]::Utc)

function Assert-Sha256([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path)) { throw "Missing pinned component: $Path" }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "Pinned component hash mismatch: $Path expected=$Expected actual=$actual"
    }
}

& (Join-Path $projectRoot 'build-windows.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

if (-not (Test-Path -LiteralPath $jlinkExe)) { throw 'Project-local jlink was not found.' }
if (-not (Test-Path -LiteralPath $sourceJar)) { throw 'Application JAR was not built.' }
if (-not (Test-Path -LiteralPath $sourceSbom)) { throw 'CycloneDX SBOM was not generated.' }
if (-not (Test-Path -LiteralPath (Join-Path $sourceOfdWorker 'doc-redaction-ofd-worker-0.1.0-poc.jar'))) {
    throw 'Isolated OFD worker was not built.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceOcr 'bin\tesseract.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceOcr 'share\tessdata\chi_sim.traineddata')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceOcr 'share\tessdata\eng.traineddata')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceOcr 'OCR_COMPONENTS.json'))) {
    throw 'Verified project-local Tesseract runtime was not prepared.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceFfmpeg 'bin\ffmpeg.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceFfmpeg 'bin\ffprobe.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceFfmpeg 'LICENSE.txt'))) {
    throw 'Verified LGPL-only FFmpeg shared runtime was not prepared.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceWhisper 'whisper-cli.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceWhisper 'whisper.dll')) -or
        -not (Test-Path -LiteralPath $sourceWhisperModel)) {
    throw 'Verified whisper.cpp runtime or multilingual model was not prepared.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceMediaModels 'face_detection_yunet_2023mar.onnx')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceMediaModels 'license_plate_detection_lpd_yunet_2023mar.onnx')) -or
        -not (Test-Path -LiteralPath $sourceMediaManifest)) {
    throw 'Verified OpenCV media models or component manifest was not prepared.'
}
if (-not (Test-Path -LiteralPath (Join-Path $sourceVlmRuntime 'cpu\llama-server.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceVlmRuntime 'vulkan\llama-server.exe')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceVlmModels 'Qwen3VL-2B-Instruct-Q4_K_M.gguf')) -or
        -not (Test-Path -LiteralPath (Join-Path $sourceVlmModels 'mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf')) -or
        -not (Test-Path -LiteralPath $sourceVlmManifest)) {
    throw 'Verified Qwen3-VL model or llama.cpp runtime was not prepared. Run prepare-vlm-windows.ps1.'
}
Assert-Sha256 (Join-Path $sourceVlmModels 'Qwen3VL-2B-Instruct-Q4_K_M.gguf') '089d75c52f4b7ffc56ba998ffc50aae89fcafc755f9e7208aacca281dca6c2ae'
Assert-Sha256 (Join-Path $sourceVlmModels 'mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf') 'f9a68fabba69c3b81e153367b2c7521030b0fa8bb0de400c9599c8e6725f9c82'
Assert-Sha256 $sourceFfmpegArchive 'c0692b85d56f2995656406425c095700117dfd7a84f8ca5af75ebf92ed08b8a9'
Assert-Sha256 $sourceWhisperArchive '49dcc16de826f20bd53d44f947a1ae49dfa81f86cad67a64d80820cb192d674a'
Assert-Sha256 $sourceWhisperModel '422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898'
Assert-Sha256 $sourceOpenCvArchive 'f0e98c302464d6860777a7015065e11b9b271b5394e6ba92663f0cf1fc303f2c'
Assert-Sha256 $sourceOpenCvJar '00e2c856933993d948910f77344ac99ce38b0f875af2866a164f8fff82e9fb5f'
Assert-Sha256 $sourceOpenCvDll '0a3fcf83e381ce5f49e155ff10e5b3a20dfc7c6590dbfb2c6c5f3dd8b684ab6c'
Assert-Sha256 (Join-Path $sourceMediaModels 'face_detection_yunet_2023mar.onnx') '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'
Assert-Sha256 (Join-Path $sourceMediaModels 'license_plate_detection_lpd_yunet_2023mar.onnx') '6d4978a7b6d25514d5e24811b82bfb511d166bdd8ca3b03aa63c1623d4d039c7'
Assert-Sha256 (Join-Path $sourceMediaSamples 'sensitive-phone-slow.wav') '3993c01d781c6b75db0f4e9a126eb9fe6fc6e289bfc472e5f1cfa01c659b43c5'
Assert-Sha256 (Join-Path $sourceMediaSamples 'sensitive-visual.mp4') 'b2e90433ca6955e8c7e68c0d2c900e076b5e0134949bd5a6b9c8f33ae1357bf7'
Assert-Sha256 (Join-Path $sourceMediaSamples 'sensitive-subtitle.mp4') 'a55e9f1b9d89acb1aa1d3c817f14fcd864bd3122b91cb04fccc48fd5d567b462'
$ffmpegVersion = (& (Join-Path $sourceFfmpeg 'bin\ffmpeg.exe') -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $ffmpegVersion -notmatch '--enable-shared' -or
        $ffmpegVersion -match '--enable-gpl' -or $ffmpegVersion -match '--enable-nonfree') {
    throw 'FFmpeg runtime failed the LGPL-only shared-build policy check.'
}

$resolvedDist = [System.IO.Path]::GetFullPath($distRoot)
$resolvedPackage = [System.IO.Path]::GetFullPath($packageRoot)
if (-not $resolvedPackage.StartsWith($resolvedDist + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to replace a package outside the dist directory.'
}

if (Test-Path -LiteralPath $packageRoot) { Remove-Item -LiteralPath $packageRoot -Recurse -Force }
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
$archiveHashPath = "$archive.sha256"
if (Test-Path -LiteralPath $archiveHashPath) { Remove-Item -LiteralPath $archiveHashPath -Force }
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'app') -Force | Out-Null

& $jlinkExe --add-modules 'java.se,jdk.httpserver,jdk.unsupported,jdk.jartool,jdk.crypto.ec' --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output (Join-Path $packageRoot 'runtime')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $packageRoot 'app\doc-redaction-poc.jar')
Copy-Item -LiteralPath $sourceOfdWorker -Destination (Join-Path $packageRoot 'app\ofd-worker') -Recurse
Copy-Item -LiteralPath $sourceOcr -Destination (Join-Path $packageRoot 'ocr') -Recurse
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'media\ffmpeg\bin') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceFfmpeg 'bin\ffmpeg.exe') -Destination (Join-Path $packageRoot 'media\ffmpeg\bin')
Copy-Item -LiteralPath (Join-Path $sourceFfmpeg 'bin\ffprobe.exe') -Destination (Join-Path $packageRoot 'media\ffmpeg\bin')
Get-ChildItem -LiteralPath (Join-Path $sourceFfmpeg 'bin') -Filter '*.dll' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $packageRoot 'media\ffmpeg\bin')
}
Copy-Item -LiteralPath (Join-Path $sourceFfmpeg 'LICENSE.txt') -Destination (Join-Path $packageRoot 'media\ffmpeg\LICENSE-LGPL-3.0.txt')
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'media\whisper') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceWhisper 'whisper-cli.exe') -Destination (Join-Path $packageRoot 'media\whisper')
Copy-Item -LiteralPath (Join-Path $sourceWhisper 'whisper.dll') -Destination (Join-Path $packageRoot 'media\whisper')
Get-ChildItem -LiteralPath $sourceWhisper -Filter 'ggml*.dll' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $packageRoot 'media\whisper')
}
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'media\models') -Force | Out-Null
Copy-Item -LiteralPath $sourceWhisperModel -Destination (Join-Path $packageRoot 'media\models\ggml-base-q5_1.bin')
Copy-Item -LiteralPath (Join-Path $sourceMediaModels 'face_detection_yunet_2023mar.onnx') -Destination (Join-Path $packageRoot 'media\models')
Copy-Item -LiteralPath (Join-Path $sourceMediaModels 'license_plate_detection_lpd_yunet_2023mar.onnx') -Destination (Join-Path $packageRoot 'media\models')
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'media\opencv\bin') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'media\opencv\licenses') -Force | Out-Null
Copy-Item -LiteralPath $sourceOpenCvDll -Destination (Join-Path $packageRoot 'media\opencv\bin')
Copy-Item -LiteralPath (Join-Path $sourceOpenCv 'LICENSE.txt') -Destination (Join-Path $packageRoot 'media\opencv\licenses\OPENCV-APACHE-2.0.txt')
Copy-Item -LiteralPath (Join-Path $sourceOpenCv 'LICENSE_FFMPEG.txt') -Destination (Join-Path $packageRoot 'media\opencv\licenses')
Copy-Item -LiteralPath (Join-Path $sourceOpenCv 'build\etc\licenses') -Destination (Join-Path $packageRoot 'media\opencv\licenses\third-party') -Recurse
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'media\licenses') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceMediaModels 'LICENSE-YUNET-MIT.txt') -Destination (Join-Path $packageRoot 'media\licenses')
Copy-Item -LiteralPath (Join-Path $sourceMediaModels 'LICENSE-LPD-APACHE-2.0.txt') -Destination (Join-Path $packageRoot 'media\licenses')
Copy-Item -Path (Join-Path $sourceMediaLicenses '*') -Destination (Join-Path $packageRoot 'media\licenses')
Copy-Item -LiteralPath $sourceMediaManifest -Destination (Join-Path $packageRoot 'media\MEDIA_COMPONENTS.json')
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'vlm\bin') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $sourceVlmRuntime 'cpu') -Destination (Join-Path $packageRoot 'vlm\bin\cpu') -Recurse
Copy-Item -LiteralPath (Join-Path $sourceVlmRuntime 'vulkan') -Destination (Join-Path $packageRoot 'vlm\bin\vulkan') -Recurse
Copy-Item -LiteralPath $sourceVlmModels -Destination (Join-Path $packageRoot 'vlm\models') -Recurse
Copy-Item -LiteralPath $sourceVlmLicenses -Destination (Join-Path $packageRoot 'vlm\licenses') -Recurse
Copy-Item -LiteralPath $sourceVlmManifest -Destination (Join-Path $packageRoot 'vlm\VLM_COMPONENTS.json')
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\start-windows.bat') -Destination (Join-Path $packageRoot 'start-windows.bat')
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\preflight-windows.ps1') -Destination (Join-Path $packageRoot 'preflight-windows.ps1')
$launcherPath = Join-Path $packageRoot 'start-windows.bat'
$launcherText = [System.IO.File]::ReadAllText($launcherPath)
$launcherText = $launcherText.Replace("`r`n", "`n").Replace("`r", "`n").Replace("`n", "`r`n")
[System.IO.File]::WriteAllText($launcherPath, $launcherText, [System.Text.Encoding]::ASCII)
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\RUNTIME_README.md') -Destination (Join-Path $packageRoot 'README.md')
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $packageRoot 'LICENSE')
Copy-Item -LiteralPath (Join-Path $projectRoot 'NOTICE') -Destination (Join-Path $packageRoot 'NOTICE')
Copy-Item -LiteralPath $sourceSbom -Destination (Join-Path $packageRoot 'SBOM.cdx.json')
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $packageRoot 'THIRD_PARTY_NOTICES.md')
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'docs') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\USER_GUIDE_ZH-CN.md') -Destination (Join-Path $packageRoot 'docs')
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\USER_GUIDE_EN.md') -Destination (Join-Path $packageRoot 'docs')
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\TROUBLESHOOTING.md') -Destination (Join-Path $packageRoot 'docs')
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\BACKUP_RESTORE_UNINSTALL.md') -Destination (Join-Path $packageRoot 'docs')
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs\LOGGING_AND_DIAGNOSTICS.md') -Destination (Join-Path $packageRoot 'docs')
Copy-Item -LiteralPath (Join-Path $projectRoot 'VERSION') -Destination (Join-Path $packageRoot 'VERSION')
Copy-Item -LiteralPath (Join-Path $projectRoot 'DATA_SCHEMA_VERSION') -Destination (Join-Path $packageRoot 'DATA_SCHEMA_VERSION')
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\update') -Destination (Join-Path $packageRoot 'update') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'samples') -Destination (Join-Path $packageRoot 'samples') -Recurse
Copy-Item -LiteralPath (Join-Path $sourceMediaSamples 'sensitive-phone-slow.wav') -Destination (Join-Path $packageRoot 'samples\media')
Copy-Item -LiteralPath (Join-Path $sourceMediaSamples 'sensitive-visual.mp4') -Destination (Join-Path $packageRoot 'samples\media')
Copy-Item -LiteralPath (Join-Path $sourceMediaSamples 'sensitive-subtitle.mp4') -Destination (Join-Path $packageRoot 'samples\media')
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'data') -Force | Out-Null

# Keep a local, hash-pinned recovery copy of every executable component. The
# launcher can repair missing or modified files without network access or
# administrator privileges.
New-Item -ItemType Directory -Path (Join-Path $packageRoot 'repair') -Force | Out-Null
$criticalRoots = @('app', 'runtime', 'ocr', 'media', 'vlm', 'update', 'docs')
$criticalFiles = foreach ($criticalRoot in $criticalRoots) {
    Get-ChildItem -LiteralPath (Join-Path $packageRoot $criticalRoot) -Recurse -File
}
$componentManifest = @(
    $criticalFiles | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($packageRoot.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
)
[System.IO.File]::WriteAllLines((Join-Path $packageRoot 'repair\COMPONENTS_SHA256SUMS.txt'),
    $componentManifest, [System.Text.UTF8Encoding]::new($false))
foreach ($criticalRoot in $criticalRoots) {
    Get-ChildItem -LiteralPath (Join-Path $packageRoot $criticalRoot) -Recurse -Force |
        ForEach-Object { $_.LastWriteTimeUtc = $stableTime }
    (Get-Item -LiteralPath (Join-Path $packageRoot $criticalRoot)).LastWriteTimeUtc = $stableTime
}
$repairArchive = Join-Path $packageRoot 'repair\components.zip'
& $jarExe --create --file $repairArchive --no-manifest `
    -C $packageRoot app -C $packageRoot runtime -C $packageRoot ocr -C $packageRoot media -C $packageRoot vlm `
    -C $packageRoot update -C $packageRoot docs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$repairHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $repairArchive).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText((Join-Path $packageRoot 'repair\REPAIR_SHA256.txt'),
    $repairHash + "`n", [System.Text.Encoding]::ASCII)

$manifest = @(
    '# SHA-256 manifest'
    (Get-ChildItem -LiteralPath $packageRoot -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($packageRoot.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    })
)
[System.IO.File]::WriteAllLines((Join-Path $packageRoot 'SHA256SUMS.txt'), $manifest,
    [System.Text.UTF8Encoding]::new($false))

Get-ChildItem -LiteralPath $packageRoot -Recurse -Force | ForEach-Object { $_.LastWriteTimeUtc = $stableTime }
(Get-Item -LiteralPath $packageRoot).LastWriteTimeUtc = $stableTime
& $jarExe --create --file $archive --no-manifest -C $distRoot (Split-Path $packageRoot -Leaf)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$archiveHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText($archiveHashPath,
    "$archiveHash  $([IO.Path]::GetFileName($archive))`n", [System.Text.Encoding]::ASCII)
Write-Host "PACKAGE_OK=$archive"
