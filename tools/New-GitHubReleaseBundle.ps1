[CmdletBinding()]
param(
    [string]$InputFile,
    [string]$OutputDirectory,
    [ValidateRange(100000000, 1900000000)]
    [long]$PartSizeBytes = 1500000000,
    [string]$ReleaseTag = 'v0.1.0-poc'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceClosureManifestPath = Join-Path $projectRoot '.tools\source-compliance\FFMPEG_SOURCE_CLOSURE.json'

if (-not (Test-Path -LiteralPath $sourceClosureManifestPath -PathType Leaf)) {
    throw 'FFmpeg source-closure manifest is missing. Run tools\Prepare-FfmpegSourceCompliance.ps1 and complete the documented compliance gate.'
}
$sourceClosure = Get-Content -LiteralPath $sourceClosureManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($sourceClosure.status -ne 'complete') {
    throw 'FFmpeg corresponding-source closure is incomplete. Binary Release generation is blocked; see packaging\FFMPEG_SOURCE_COMPLIANCE.md.'
}

if ([string]::IsNullOrWhiteSpace($InputFile)) {
    $InputFile = Join-Path $projectRoot 'dist\doc-redaction-poc-windows-x64.zip'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot 'release-assets\v0.1.0-poc'
}

$inputItem = Get-Item -LiteralPath $InputFile -ErrorAction Stop
if ($inputItem.PSIsContainer) {
    throw "Input must be a file: $($inputItem.FullName)"
}

$outputFullPath = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $outputFullPath) {
    $existing = @(Get-ChildItem -LiteralPath $outputFullPath -Force)
    if ($existing.Count -gt 0) {
        throw "Output directory is not empty. Use a new directory instead of overwriting release evidence: $outputFullPath"
    }
} else {
    New-Item -ItemType Directory -Path $outputFullPath -Force | Out-Null
}

$buffer = New-Object byte[] (8MB)
$parts = New-Object System.Collections.Generic.List[object]
$partNumber = 1
$inputStream = [IO.File]::Open($inputItem.FullName, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
try {
    while ($inputStream.Position -lt $inputStream.Length) {
        $partName = '{0}.part{1:D3}' -f $inputItem.Name, $partNumber
        $partPath = Join-Path $outputFullPath $partName
        $outputStream = [IO.File]::Open($partPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        $written = 0L
        try {
            $targetBytes = [Math]::Min($PartSizeBytes, $inputStream.Length - $inputStream.Position)
            while ($written -lt $targetBytes) {
                $requested = [int][Math]::Min($buffer.Length, $targetBytes - $written)
                $read = $inputStream.Read($buffer, 0, $requested)
                if ($read -le 0) {
                    throw "Unexpected end of input while creating $partName"
                }
                $outputStream.Write($buffer, 0, $read)
                $written += $read
            }
            $outputStream.Flush()
        } finally {
            $outputStream.Dispose()
        }
        $parts.Add([ordered]@{
            order = $partNumber
            file = $partName
            bytes = $written
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $partPath).Hash.ToLowerInvariant()
        })
        $partNumber++
    }
} finally {
    $inputStream.Dispose()
}

$finalHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $inputItem.FullName).Hash.ToLowerInvariant()
$utf8NoBom = New-Object Text.UTF8Encoding($false)

$partNames = @($parts | ForEach-Object { $_.file })
[IO.File]::WriteAllLines((Join-Path $outputFullPath 'PARTS.txt'), $partNames, $utf8NoBom)

$partHashes = @($parts | ForEach-Object { '{0}  {1}' -f $_.sha256, $_.file })
[IO.File]::WriteAllLines((Join-Path $outputFullPath 'PART_SHA256SUMS.txt'), $partHashes, $utf8NoBom)
[IO.File]::WriteAllLines(
    (Join-Path $outputFullPath 'FINAL_SHA256.txt'),
    @('{0}  {1}' -f $finalHash, $inputItem.Name),
    $utf8NoBom
)

$manifest = [ordered]@{
    schemaVersion = 1
    releaseTag = $ReleaseTag
    generatedAt = [DateTime]::UtcNow.ToString('o')
    originalFile = $inputItem.Name
    originalBytes = $inputItem.Length
    originalSha256 = $finalHash
    githubReleasePerFileLimitBytes = 2147483648
    configuredPartSizeBytes = $PartSizeBytes
    partCount = $parts.Count
    # Windows PowerShell 5.1 cannot always bind @($genericList) during JSON serialization.
    parts = $parts.ToArray()
}
[IO.File]::WriteAllText(
    (Join-Path $outputFullPath 'RELEASE_ASSETS.json'),
    ($manifest | ConvertTo-Json -Depth 6),
    $utf8NoBom
)

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Join-GitHubReleaseBundle.ps1') -Destination (Join-Path $outputFullPath 'join-windows.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'join-github-release-bundle.sh') -Destination (Join-Path $outputFullPath 'join-unix.sh')
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\GITHUB_RELEASE_README.md') -Destination (Join-Path $outputFullPath 'README-RELEASE.md')
Copy-Item -LiteralPath (Join-Path $projectRoot 'dist\doc-redaction-poc-windows-x64\SBOM.cdx.json') -Destination (Join-Path $outputFullPath 'SBOM.cdx.json')
Copy-Item -LiteralPath (Join-Path $projectRoot 'audit\BUILD_MANIFEST.json') -Destination (Join-Path $outputFullPath 'BUILD_MANIFEST.json')
Copy-Item -LiteralPath (Join-Path $projectRoot 'audit\results\security-gate-latest.json') -Destination (Join-Path $outputFullPath 'SECURITY_GATE.json')
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $outputFullPath 'THIRD_PARTY_NOTICES.md')
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $outputFullPath 'LICENSE')
$sourceClosure.verifiedArtifacts | ForEach-Object {
    $sourcePath = Join-Path (Split-Path -Parent $sourceClosureManifestPath) $_.file
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Verified source artifact is missing: $($_.file)"
    }
    $sourceItem = Get-Item -LiteralPath $sourcePath
    $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceItem.Length -ne [long]$_.bytes -or $sourceHash -ne $_.sha256) {
        throw "Verified source artifact changed: $($_.file)"
    }
    Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $outputFullPath $_.file)
}
Copy-Item -LiteralPath $sourceClosureManifestPath -Destination (Join-Path $outputFullPath 'FFMPEG_SOURCE_CLOSURE.json')
Copy-Item -LiteralPath (Join-Path $projectRoot 'packaging\FFMPEG_SOURCE_COMPLIANCE.md') `
    -Destination (Join-Path $outputFullPath 'FFMPEG_SOURCE_COMPLIANCE.md')

$assetFiles = Get-ChildItem -LiteralPath $outputFullPath -File | Where-Object { $_.Name -ne 'RELEASE_SHA256SUMS.txt' } | Sort-Object Name
$assetHashes = foreach ($asset in $assetFiles) {
    '{0}  {1}' -f (Get-FileHash -Algorithm SHA256 -LiteralPath $asset.FullName).Hash.ToLowerInvariant(), $asset.Name
}
[IO.File]::WriteAllLines((Join-Path $outputFullPath 'RELEASE_SHA256SUMS.txt'), @($assetHashes), $utf8NoBom)

$largestPart = ($parts | ForEach-Object { [long]$_['bytes'] } | Measure-Object -Maximum).Maximum
if ($largestPart -ge 2147483648) {
    throw "A generated part violates GitHub's under-2-GiB Release asset limit: $largestPart bytes"
}

[ordered]@{
    status = 'ready'
    outputDirectory = $outputFullPath
    originalBytes = $inputItem.Length
    originalSha256 = $finalHash
    partCount = $parts.Count
    largestPartBytes = $largestPart
} | ConvertTo-Json
