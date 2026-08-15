param(
    [Parameter(Mandatory = $true)]
    [string]$PackageRoot
)

$ErrorActionPreference = 'Stop'
$root = [System.IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
$separator = [System.IO.Path]::DirectorySeparatorChar
$diagnostics = Join-Path $root 'data\diagnostics'
$reportPath = Join-Path $diagnostics 'environment-report.json'
$optionsPath = Join-Path $diagnostics 'runtime-options.cmd'
$issues = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]
$repaired = $false

function Test-WithinPackage([string]$Path) {
    $resolved = [System.IO.Path]::GetFullPath($Path)
    return $resolved.StartsWith($root + $separator, [System.StringComparison]::OrdinalIgnoreCase)
}

function Read-ComponentFailures {
    $manifestPath = Join-Path $root 'repair\COMPONENTS_SHA256SUMS.txt'
    $failures = New-Object System.Collections.Generic.List[string]
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        $failures.Add('The critical-component manifest is missing.')
        return $failures
    }
    $expectedPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($line in Get-Content -LiteralPath $manifestPath -Encoding UTF8) {
        if ($line -notmatch '^([0-9a-fA-F]{64})  (.+)$') {
            $failures.Add('The critical-component manifest contains an invalid line.')
            continue
        }
        $relative = $Matches[2].Replace('/', '\')
        [void]$expectedPaths.Add($relative)
        $target = Join-Path $root $relative
        if (-not (Test-WithinPackage $target)) {
            $failures.Add("Unsafe component path: $relative")
            continue
        }
        if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
            $failures.Add("Missing component: $relative")
            continue
        }
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash
        if ($actual -ne $Matches[1]) {
            $failures.Add("Hash mismatch: $relative")
        }
    }
    foreach ($componentRoot in @('app', 'runtime', 'ocr', 'media', 'vlm', 'update')) {
        $directory = Join-Path $root $componentRoot
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
        Get-ChildItem -LiteralPath $directory -Recurse -File | ForEach-Object {
            $relative = $_.FullName.Substring($root.Length).TrimStart('\')
            if (-not $expectedPaths.Contains($relative)) { $failures.Add("Unexpected component: $relative") }
        }
    }
    return $failures
}

function Restore-Components {
    $archive = Join-Path $root 'repair\components.zip'
    $archiveHashFile = Join-Path $root 'repair\REPAIR_SHA256.txt'
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or
            -not (Test-Path -LiteralPath $archiveHashFile -PathType Leaf)) {
        throw 'The offline component-repair archive is unavailable.'
    }
    $expected = (Get-Content -LiteralPath $archiveHashFile -Encoding ASCII -Raw).Trim().ToLowerInvariant()
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$' -or $expected -ne $actual) {
        throw 'The offline component-repair archive failed its SHA-256 check.'
    }
    $temporary = Join-Path $root ('repair-work-' + [Guid]::NewGuid().ToString('N'))
    if (-not (Test-WithinPackage $temporary)) {
        throw 'Refusing to create a repair directory outside the package.'
    }
    try {
        Expand-Archive -LiteralPath $archive -DestinationPath $temporary -Force
        foreach ($name in @('app', 'runtime', 'ocr', 'media', 'vlm', 'update')) {
            $source = Join-Path $temporary $name
            $target = Join-Path $root $name
            if (-not (Test-Path -LiteralPath $source -PathType Container)) {
                throw "The repair archive does not contain $name."
            }
            if (-not (Test-WithinPackage $target)) { throw "Unsafe repair target: $target" }
            if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
            Copy-Item -LiteralPath $source -Destination $target -Recurse -Force
        }
    } finally {
        if ((Test-Path -LiteralPath $temporary) -and (Test-WithinPackage $temporary)) {
            Remove-Item -LiteralPath $temporary -Recurse -Force
        }
    }
}

function Test-Executable([string]$Name, [string]$RelativeFile, [string[]]$Arguments) {
    $executable = Join-Path $root $RelativeFile
    $probeId = [Guid]::NewGuid().ToString('N')
    $standardOutput = Join-Path $diagnostics ("probe-$probeId.out")
    $standardError = Join-Path $diagnostics ("probe-$probeId.err")
    try {
        $safeArguments = @($Arguments | ForEach-Object {
            if ($_ -match '[\s"]') { '"' + $_.Replace('"', '\"') + '"' } else { $_ }
        })
        $process = Start-Process -FilePath $executable -ArgumentList $safeArguments -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $standardOutput -RedirectStandardError $standardError
        if ($process.ExitCode -ne 0) { throw "exit code $($process.ExitCode)" }
    } catch {
        $issues.Add("$Name cannot run on this machine: $($_.Exception.Message)")
    } finally {
        if (Test-Path -LiteralPath $standardOutput) { Remove-Item -LiteralPath $standardOutput -Force }
        if (Test-Path -LiteralPath $standardError) { Remove-Item -LiteralPath $standardError -Force }
    }
}

try {
    New-Item -ItemType Directory -Path $diagnostics -Force | Out-Null
    $writeProbe = Join-Path $diagnostics ('write-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    [System.IO.File]::WriteAllText($writeProbe, 'ok', [System.Text.Encoding]::ASCII)
    Remove-Item -LiteralPath $writeProbe -Force
} catch {
    Write-Error 'The package data directory is not writable. Move the extracted package to a writable local directory.'
    exit 20
}

$componentFailures = @(Read-ComponentFailures)
if ($componentFailures.Count -gt 0) {
    try {
        Restore-Components
        $repaired = $true
        $componentFailures = @(Read-ComponentFailures)
    } catch {
        $issues.Add($_.Exception.Message)
    }
}
foreach ($failure in $componentFailures) { $issues.Add($failure) }

try {
    $os = Get-CimInstance Win32_OperatingSystem
    $osVersion = [Version]$os.Version
    $osCaption = [string]$os.Caption
} catch {
    $osVersion = [Environment]::OSVersion.Version
    $osCaption = [Environment]::OSVersion.VersionString
    $warnings.Add('Win32_OperatingSystem could not be queried; the process OS version was used.')
}
if ($osVersion.Major -lt 10) { $issues.Add('Windows 10 or later is required.') }
if (-not [Environment]::Is64BitOperatingSystem) { $issues.Add('A 64-bit Windows installation is required.') }
$architecture = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
if ($architecture -notmatch '^(AMD64|ARM64)$') { $issues.Add("Unsupported processor architecture: $architecture") }
if ($architecture -eq 'ARM64') { $issues.Add('This package contains x64 native components. Use a separately verified ARM64 build.') }

try {
    $memoryBytes = [int64](Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
} catch {
    $memoryBytes = 0
    $issues.Add('Physical memory could not be detected.')
}
$driveRoot = [System.IO.Path]::GetPathRoot($root)
try {
    $diskBytes = [int64](Get-CimInstance Win32_LogicalDisk -Filter ("DeviceID='" + $driveRoot.TrimEnd('\') + "'")).FreeSpace
} catch {
    $diskBytes = 0
    $issues.Add('Free disk space could not be detected.')
}
if ($memoryBytes -lt 4GB) { $issues.Add('At least 4 GiB of physical memory is required.') }
if ($diskBytes -lt 4GB) { $issues.Add('At least 4 GiB of free disk space is required to start safely.') }

$vlmMode = if ($memoryBytes -ge 8GB) { 'auto' } else { 'disabled' }
$vlmBackend = 'cpu'
$vlmVideoMemoryMiB = 0
try {
    $nvidia = & nvidia-smi.exe --query-gpu=memory.total --format=csv,noheader,nounits 2>$null
    $values = @($nvidia | ForEach-Object { if ($_ -match '^\s*(\d+)') { [int]$Matches[1] } })
    if ($values.Count -gt 0) { $vlmVideoMemoryMiB = ($values | Measure-Object -Maximum).Maximum }
} catch { }
if ($vlmVideoMemoryMiB -ge 4096 -and (Test-Path -LiteralPath (Join-Path $root 'vlm\bin\vulkan\llama-server.exe'))) {
    $vlmBackend = 'vulkan'
}
if ($vlmMode -eq 'disabled') {
    $warnings.Add('Qwen3-VL was disabled because less than 8 GiB of physical memory was detected; deterministic rules and OCR remain available.')
}

$logicalProcessors = [Math]::Max(1, [Environment]::ProcessorCount)
if ($memoryBytes -ge 16GB) {
    $profile = 'standard'
    $appHeap = '4096m'
    $workerHeap = '2048m'
    $workerCommitLimit = '4096m'
    $workerConcurrency = if ($logicalProcessors -ge 8) { 2 } else { 1 }
    $sampleFrames = 900
    $asrThreads = [Math]::Min(8, [Math]::Max(2, $logicalProcessors - 1))
} elseif ($memoryBytes -ge 8GB) {
    $profile = 'balanced'
    $appHeap = '2048m'
    $workerHeap = '1536m'
    $workerCommitLimit = '3584m'
    $workerConcurrency = 1
    $sampleFrames = 600
    $asrThreads = [Math]::Min(4, [Math]::Max(2, $logicalProcessors - 1))
} else {
    $profile = 'constrained'
    $appHeap = '1024m'
    $workerHeap = '768m'
    $workerCommitLimit = '2048m'
    $workerConcurrency = 1
    $sampleFrames = 300
    $asrThreads = [Math]::Min(2, $logicalProcessors)
    $warnings.Add('Constrained mode reduces worker memory and sampled video frames. Large files may be refused safely.')
}
if ($vlmMode -eq 'auto' -and $workerConcurrency -gt 1) {
    $workerConcurrency = 1
    $warnings.Add('Worker concurrency was limited to 1 to prevent duplicate Qwen3-VL model instances from exhausting memory.')
}

if ($issues.Count -eq 0) {
    Test-Executable 'Java' 'runtime\bin\java.exe' @('-version')
    Test-Executable 'Tesseract' 'ocr\bin\tesseract.exe' @('--version')
    Test-Executable 'FFmpeg' 'media\ffmpeg\bin\ffmpeg.exe' @('-version')
    Test-Executable 'FFprobe' 'media\ffmpeg\bin\ffprobe.exe' @('-version')
    Test-Executable 'whisper.cpp' 'media\whisper\whisper-cli.exe' @('--version')
    Test-Executable 'OpenCV JNI' 'runtime\bin\java.exe' @(
        ('-Ddocredaction.media.opencvLibrary=' + (Join-Path $root 'media\opencv\bin\opencv_java4130.dll')),
        '-cp', (Join-Path $root 'app\doc-redaction-poc.jar'), 'io.github.caipeijia833.docredaction.Main', '--opencv-probe')
    Test-Executable 'llama.cpp' ("vlm\bin\$vlmBackend\llama-server.exe") @('--version')
}

$status = if ($issues.Count -eq 0) { 'ready' } else { 'blocked' }
$report = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    status = $status
    offline = $true
    repairAttempted = $repaired
    packageRoot = $root
    os = [ordered]@{ caption = $osCaption; version = $osVersion.ToString(); architecture = $architecture }
    resources = [ordered]@{ physicalMemoryBytes = $memoryBytes; freeDiskBytes = $diskBytes; logicalProcessors = $logicalProcessors }
    profile = [ordered]@{ name = $profile; appHeap = $appHeap; workerHeap = $workerHeap; workerCommitLimit = $workerCommitLimit; workerConcurrency = $workerConcurrency; mediaSampleFrames = $sampleFrames; asrThreads = $asrThreads; vlmMode = $vlmMode; vlmBackend = $vlmBackend; detectedVideoMemoryMiB = $vlmVideoMemoryMiB }
    issues = @($issues)
    warnings = @($warnings)
}
[System.IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))

if ($issues.Count -gt 0) {
    Write-Host 'ENVIRONMENT_BLOCKED'
    foreach ($issue in $issues) { Write-Host ("ERROR: " + $issue) }
    Write-Host ("REPORT=" + $reportPath)
    exit 21
}

$options = @(
    '@echo off',
    ('set "DOC_REDACTION_ENV_PROFILE=' + $profile + '"'),
    ('set "DOC_REDACTION_APP_XMX=' + $appHeap + '"'),
    ('set "DOC_REDACTION_WORKER_XMX=' + $workerHeap + '"'),
    ('set "DOC_REDACTION_WORKER_COMMIT_LIMIT=' + $workerCommitLimit + '"'),
    ('set "DOC_REDACTION_WORKER_CONCURRENCY=' + $workerConcurrency + '"'),
    ('set "DOC_REDACTION_MEDIA_SAMPLE_FRAMES=' + $sampleFrames + '"'),
    ('set "DOC_REDACTION_ASR_THREADS=' + $asrThreads + '"'),
    ('set "DOC_REDACTION_VLM_MODE=' + $vlmMode + '"'),
    ('set "DOC_REDACTION_VLM_RELATIVE_EXE=vlm\bin\' + $vlmBackend + '\llama-server.exe"')
)
[System.IO.File]::WriteAllLines($optionsPath, $options, [System.Text.Encoding]::ASCII)
Write-Host ("ENVIRONMENT_READY=" + $profile)
if ($repaired) { Write-Host 'COMPONENTS_REPAIRED=1' }
Write-Host ("REPORT=" + $reportPath)
exit 0
