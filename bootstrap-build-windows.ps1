[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$toolsRoot = Join-Path $projectRoot '.tools'
$downloads = Join-Path $toolsRoot 'downloads'
$jdkDist = Join-Path $toolsRoot 'jdk-dist'
$jdkHome = Join-Path $jdkDist 'jdk-21.0.12+8'
$archive = Join-Path $downloads 'OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip'
$uri = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12_8.zip'
$expectedSha256 = '9ba963ee2371874a74185d18bc7bb2ab9407df7683300855ed7606e0662321d0'

if (-not [Environment]::Is64BitOperatingSystem) {
    throw 'The source build supports only 64-bit Windows.'
}
if (Test-Path -LiteralPath (Join-Path $jdkHome 'bin\java.exe') -PathType Leaf) {
    Write-Host "JDK_READY=$jdkHome"
    exit 0
}

New-Item -ItemType Directory -Path $downloads -Force | Out-Null
New-Item -ItemType Directory -Path $jdkDist -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    Write-Host 'Java 21 is missing; downloading the pinned Temurin archive for the source build.'
    Invoke-WebRequest -UseBasicParsing -Uri $uri -OutFile $archive
}

$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant()
if ($actual -ne $expectedSha256) {
    throw "Temurin archive SHA-256 mismatch: $actual"
}

$resolvedTools = [System.IO.Path]::GetFullPath($toolsRoot)
$resolvedJdkDist = [System.IO.Path]::GetFullPath($jdkDist)
if (-not $resolvedJdkDist.StartsWith($resolvedTools + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to extract the JDK outside the project .tools directory.'
}

$staging = Join-Path $toolsRoot ('jdk-staging-' + [Guid]::NewGuid().ToString('N'))
try {
    Expand-Archive -LiteralPath $archive -DestinationPath $staging -Force
    $extracted = Get-ChildItem -LiteralPath $staging -Directory | Select-Object -First 1
    if (-not $extracted -or -not (Test-Path -LiteralPath (Join-Path $extracted.FullName 'bin\java.exe'))) {
        throw 'The Temurin archive did not contain the expected JDK layout.'
    }
    if (Test-Path -LiteralPath $jdkHome) {
        Remove-Item -LiteralPath $jdkHome -Recurse -Force
    }
    Move-Item -LiteralPath $extracted.FullName -Destination $jdkHome
} finally {
    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
}

$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $version = (& (Join-Path $jdkHome 'bin\java.exe') -version 2>&1 | Out-String)
    $javaExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($javaExitCode -ne 0 -or $version -notmatch 'version "21(?:\.|"|\s)') {
    throw 'The bootstrapped JDK did not report Java 21.'
}
Write-Host "JDK_READY=$jdkHome"
