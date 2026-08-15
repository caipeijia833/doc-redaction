param(
    [switch]$RefreshVulnerabilityDatabase,
    [switch]$SkipBuildAndTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$resultsRoot = Join-Path $PSScriptRoot 'results'
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
$runRoot = Join-Path $resultsRoot "security-gate-$runId"
$failures = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null

function Add-Check([string]$Name, [bool]$Passed, [string]$Detail) {
    $checks.Add([ordered]@{ name = $Name; passed = $Passed; detail = $Detail })
    if (-not $Passed) { $failures.Add("$Name`: $Detail") }
}

function Invoke-Captured([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$LogPath) {
    $previousPreference = $ErrorActionPreference
    try {
        # Native CLIs legitimately write status lines to stderr. Their exit code,
        # not PowerShell's NativeCommandError wrapper, is the gate result.
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments *> $LogPath
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    # Raw logs stay in the ignored per-run directory. The tracked public
    # summary records only the result so it never publishes workstation paths.
    Add-Check $Name ($code -eq 0) "exitCode=$code; internalLogRetainedLocally=true"
    return $code
}

if ($env:OS -ne 'Windows_NT') {
    throw 'This gate script is the Windows implementation. Run the macOS gate from a macOS package build.'
}

& (Join-Path $PSScriptRoot 'prepare-security-tools-windows.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Security tool preparation failed.' }

$javaHome = Join-Path $projectRoot '.tools\jdk-dist\jdk-21.0.12+8'
$gradle = Join-Path $projectRoot '.tools\gradle-dist\gradle-8.14.3\bin\gradle.bat'
$ocrRoot = Join-Path $projectRoot '.tools\ocr-minimal-build\runtime'
$opencvRoot = Join-Path $projectRoot '.tools\opencv-official-4.13.0\opencv'
$env:JAVA_HOME = $javaHome

if (-not $SkipBuildAndTests) {
    $gradleArguments = @('--no-daemon', '--project-dir', (Join-Path $projectRoot 'app'),
        "-PocrRoot=$ocrRoot", "-PopencvRoot=$opencvRoot", 'test', 'nativeIntegrationTest', 'build', 'cyclonedxDirectBom')
    $mediaSpeech = Join-Path $projectRoot '.tools\media-smoke\sensitive-phone-slow.wav'
    $mediaVideo = Join-Path $projectRoot '.tools\media-smoke\sensitive-visual.mp4'
    $mediaSubtitle = Join-Path $projectRoot '.tools\media-smoke\sensitive-subtitle.mp4'
    $visualSamples = Join-Path $projectRoot '.tools\media-smoke\opencv-official'
    if ((Test-Path -LiteralPath $mediaSpeech) -and (Test-Path -LiteralPath $mediaVideo) -and
            (Test-Path -LiteralPath $mediaSubtitle) -and
            (Test-Path -LiteralPath (Join-Path $visualSamples 'largest_selfie.jpg')) -and
            (Test-Path -LiteralPath (Join-Path $visualSamples 'plate-result-1.jpg')) -and
            (Test-Path -LiteralPath (Join-Path $visualSamples 'qr-generated.png'))) {
        $gradleArguments = @('--no-daemon', '--project-dir', (Join-Path $projectRoot 'app'),
            "-PocrRoot=$ocrRoot", "-PopencvRoot=$opencvRoot", "-PmediaTestRoot=$(Join-Path $projectRoot '.tools')",
            "-PmediaSpeechSample=$mediaSpeech", "-PmediaVideoSample=$mediaVideo",
            "-PmediaSubtitleSample=$mediaSubtitle", "-PopencvVisualSamples=$visualSamples",
            'test', 'nativeIntegrationTest', 'build', 'cyclonedxDirectBom')
    }
    [void](Invoke-Captured 'build-test-sbom' $gradle $gradleArguments (Join-Path $runRoot 'build-test-sbom.log'))
}

$sbom = Join-Path $projectRoot 'app\build\reports\cyclonedx-direct\bom.json'
Add-Check 'cyclonedx-sbom-present' (Test-Path -LiteralPath $sbom -PathType Leaf) 'app/build/reports/cyclonedx-direct/bom.json'

$gitleaks = Join-Path $projectRoot '.tools\security-gate\gitleaks-8.30.0\gitleaks.exe'
$gitleaksReport = Join-Path $runRoot 'gitleaks.json'
$gitleaksArguments = @('dir', '--no-banner', '--no-color', '--redact=100', '--timeout=300',
    '--max-target-megabytes=16', '--config', (Join-Path $projectRoot '.gitleaks.toml'),
    '--report-format=json', "--report-path=$gitleaksReport", $projectRoot)
[void](Invoke-Captured 'secret-scan' $gitleaks $gitleaksArguments (Join-Path $runRoot 'gitleaks.log'))
if (-not (Test-Path -LiteralPath $gitleaksReport)) {
    [IO.File]::WriteAllText($gitleaksReport, '[]', [Text.UTF8Encoding]::new($false))
}

$osv = Join-Path $projectRoot '.tools\security-gate\osv-scanner-2.4.0.exe'
$osvReport = Join-Path $runRoot 'osv.json'
$env:OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY = Join-Path $projectRoot '.tools\security-gate\osv-cache'
New-Item -ItemType Directory -Path $env:OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY -Force | Out-Null
$databaseSnapshotBefore = Get-ChildItem -LiteralPath $env:OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY `
    -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
$databaseSnapshotBeforeUtc = if ($null -ne $databaseSnapshotBefore) {
    $databaseSnapshotBefore.LastWriteTimeUtc.ToString('o')
} else { $null }
if (Test-Path -LiteralPath $sbom -PathType Leaf) {
    $osvArguments = @('scan', 'source', '--sbom', $sbom, '--offline-vulnerabilities',
        '--format=json', "--output-file=$osvReport", '--verbosity=error')
    if ($RefreshVulnerabilityDatabase) {
        $osvArguments += '--download-offline-databases'
    } else {
        $osvArguments += '--offline'
    }
    [void](Invoke-Captured 'offline-vulnerability-scan' $osv $osvArguments (Join-Path $runRoot 'osv.log'))
} else {
    Add-Check 'offline-vulnerability-scan' $false 'SBOM is unavailable'
}

$lock = (Get-Content -LiteralPath (Join-Path $projectRoot 'app\gradle.lockfile') -Raw -Encoding UTF8) + "`n" +
    (Get-Content -LiteralPath (Join-Path $projectRoot 'app\gradle\verification-metadata.xml') -Raw -Encoding UTF8)
$forbiddenCoordinates = @('com.itextpdf:', 'org.openpnp:opencv:', 'org.json:json:20141113',
    'org.apache.logging.log4j:log4j-api:2.16.0')
$forbiddenHits = @($forbiddenCoordinates | Where-Object { $lock.Contains($_) })
Add-Check 'forbidden-dependency-policy' ($forbiddenHits.Count -eq 0) `
    ($(if ($forbiddenHits.Count -eq 0) { 'no forbidden coordinates' } else { $forbiddenHits -join ', ' }))

$sourceRoot = Join-Path $projectRoot 'app\src\main\java'
$obsoleteOpenCv = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' | Select-String -SimpleMatch 'nu.pattern.OpenCV')
Add-Check 'official-opencv-api-only' ($obsoleteOpenCv.Count -eq 0) "obsoleteApiHits=$($obsoleteOpenCv.Count)"
$opencvDll = Join-Path $opencvRoot 'build\java\x64\opencv_java4130.dll'
$opencvHash = if (Test-Path -LiteralPath $opencvDll) {
    (Get-FileHash -LiteralPath $opencvDll -Algorithm SHA256).Hash.ToLowerInvariant()
} else { '' }
Add-Check 'official-opencv-native-hash' `
    ($opencvHash -eq '0a3fcf83e381ce5f49e155ff10e5b3a20dfc7c6590dbfb2c6c5f3dd8b684ab6c') `
    "sha256=$opencvHash"

$gateStatus = if ($failures.Count -eq 0) { 'passed' } else { 'blocked' }
$databaseSnapshot = Get-ChildItem -LiteralPath $env:OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY `
    -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
$summary = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    status = $gateStatus
    sourceSentToExternalScanner = $false
    networkUse = $(if ($RefreshVulnerabilityDatabase) {
        'official OSV vulnerability database refresh only'
    } else { 'none' })
    vulnerabilityDatabaseMode = 'offline-cache'
    vulnerabilityDatabaseRefreshRequested = [bool]$RefreshVulnerabilityDatabase
    vulnerabilityDatabaseChangedThisRun = ($databaseSnapshotBeforeUtc -ne $(if ($null -ne $databaseSnapshot) {
        $databaseSnapshot.LastWriteTimeUtc.ToString('o')
    } else { $null }))
    vulnerabilityDatabaseSnapshotUtc = $(if ($null -ne $databaseSnapshot) {
        $databaseSnapshot.LastWriteTimeUtc.ToString('o')
    } else { $null })
    checks = $checks.ToArray()
    failures = $failures.ToArray()
}
$summaryPath = Join-Path $runRoot 'summary.json'
[IO.File]::WriteAllText($summaryPath, ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath $summaryPath -Destination (Join-Path $resultsRoot 'security-gate-latest.json') -Force

if ($failures.Count -gt 0) {
    Write-Host 'SECURITY_GATE_BLOCKED'
    $failures | ForEach-Object { Write-Host "ERROR: $_" }
    Write-Host "REPORT=$summaryPath"
    exit 40
}
Write-Host 'SECURITY_GATE_PASSED'
Write-Host "REPORT=$summaryPath"
exit 0
