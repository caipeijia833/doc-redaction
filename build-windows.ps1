[CmdletBinding()]
param(
    [switch]$WithNativeTests,
    [switch]$SkipJdkBootstrap
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$appRoot = Join-Path $projectRoot 'app'
$gradleWrapper = Join-Path $appRoot 'gradlew.bat'
$localGradle = Join-Path $projectRoot '.tools\gradle-dist\gradle-8.14.3\bin\gradle.bat'

function Test-Java21([string]$JavaExecutable) {
    if (-not (Test-Path -LiteralPath $JavaExecutable -PathType Leaf)) { return $false }
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = (& $JavaExecutable -version 2>&1 | Out-String)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return $exitCode -eq 0 -and $output -match 'version "21(?:\.|"|\s)'
}

function Resolve-JavaHome {
    $projectJdk = Join-Path $projectRoot '.tools\jdk-dist\jdk-21.0.12+8'
    if (Test-Java21 (Join-Path $projectJdk 'bin\java.exe')) { return $projectJdk }

    if ($env:JAVA_HOME -and (Test-Java21 (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return [System.IO.Path]::GetFullPath($env:JAVA_HOME)
    }

    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java -and (Test-Java21 $java.Source)) {
        return Split-Path -Parent (Split-Path -Parent $java.Source)
    }

    if ($SkipJdkBootstrap) {
        throw 'Java 21 was not found. Install Temurin/OpenJDK 21 or run without -SkipJdkBootstrap.'
    }
    & (Join-Path $projectRoot 'bootstrap-build-windows.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (-not (Test-Java21 (Join-Path $projectJdk 'bin\java.exe'))) {
        throw 'The project-local Java 21 bootstrap did not produce a valid runtime.'
    }
    return $projectJdk
}

if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    throw 'Gradle Wrapper is missing from app/gradlew.bat.'
}

$env:JAVA_HOME = Resolve-JavaHome
$gradleExecutable = if (Test-Path -LiteralPath $localGradle -PathType Leaf) {
    $localGradle
} else {
    $gradleWrapper
}
$gradleArgs = @('--no-daemon', 'clean', 'test', 'build', 'cyclonedxDirectBom')

if ($WithNativeTests) {
    & (Join-Path $projectRoot 'audit\build-minimal-tesseract-windows.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & (Join-Path $projectRoot 'prepare-opencv-windows.ps1')
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $ocrRoot = Join-Path $projectRoot '.tools\ocr-minimal-build\runtime'
    $opencvRoot = Join-Path $projectRoot '.tools\opencv-official-4.13.0\opencv'
    $gradleArgs = @('--no-daemon', "-PocrRoot=$ocrRoot", "-PopencvRoot=$opencvRoot")

    $mediaSpeech = Join-Path $projectRoot '.tools\media-smoke\sensitive-phone-slow.wav'
    $mediaVideo = Join-Path $projectRoot '.tools\media-smoke\sensitive-visual.mp4'
    $mediaSubtitle = Join-Path $projectRoot '.tools\media-smoke\sensitive-subtitle.mp4'
    $visualSamples = Join-Path $projectRoot '.tools\media-smoke\opencv-official'
    if ((Test-Path -LiteralPath $mediaSpeech) -and (Test-Path -LiteralPath $mediaVideo) -and
            (Test-Path -LiteralPath $mediaSubtitle) -and
            (Test-Path -LiteralPath (Join-Path $visualSamples 'largest_selfie.jpg')) -and
            (Test-Path -LiteralPath (Join-Path $visualSamples 'plate-result-1.jpg')) -and
            (Test-Path -LiteralPath (Join-Path $visualSamples 'qr-generated.png'))) {
        $gradleArgs += @("-PmediaTestRoot=$(Join-Path $projectRoot '.tools')",
            "-PmediaSpeechSample=$mediaSpeech", "-PmediaVideoSample=$mediaVideo",
            "-PmediaSubtitleSample=$mediaSubtitle", "-PopencvVisualSamples=$visualSamples")
    }
    $gradleArgs += @('clean', 'test', 'nativeIntegrationTest', 'build', 'cyclonedxDirectBom')
}

Push-Location $appRoot
try {
    & $gradleExecutable @gradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}

Write-Host "BUILD_OK javaHome=$env:JAVA_HOME gradle=$gradleExecutable nativeTests=$([bool]$WithNativeTests)"
