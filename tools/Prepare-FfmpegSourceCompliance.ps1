[CmdletBinding()]
param(
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot '.tools\source-compliance'
}
$outputFullPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputFullPath | Out-Null

$sources = @(
    [ordered]@{
        name = 'FFmpeg source'
        commit = '9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b'
        file = 'ffmpeg-9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b.tar.gz'
        bytes = 16903934
        sha256 = '7e779215eae16ad7e93ddad59bd82822bd3d34e4dc61f9996f9481b2c0605bc3'
        url = 'https://github.com/FFmpeg/FFmpeg/archive/9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b.tar.gz'
    },
    [ordered]@{
        name = 'BtbN FFmpeg-Builds recipe'
        commit = '2a3249ec58228c661e7ff8fdc9ea997b18aa912b'
        file = 'ffmpeg-builds-2a3249ec58228c661e7ff8fdc9ea997b18aa912b.tar.gz'
        bytes = 101851
        sha256 = 'de512332bdb8561da94e977b59a3c5b7046e61555a280c5677da21e777197811'
        url = 'https://github.com/BtbN/FFmpeg-Builds/archive/2a3249ec58228c661e7ff8fdc9ea997b18aa912b.tar.gz'
    }
)

foreach ($source in $sources) {
    $path = Join-Path $outputFullPath $source.file
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Invoke-WebRequest -UseBasicParsing -Uri $source.url -OutFile $path
    }
    $item = Get-Item -LiteralPath $path
    $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($item.Length -ne $source.bytes -or $actualHash -ne $source.sha256) {
        throw "Source archive verification failed: $($source.file)"
    }
}

# This manifest is deliberately incomplete. The selected BtbN build statically
# links many optional third-party libraries into the FFmpeg shared libraries.
# FFmpeg source plus the build recipe alone is not asserted to be the complete
# corresponding-source closure for every LGPL-covered linked component.
$manifest = [ordered]@{
    schemaVersion = 1
    status = 'incomplete'
    binaryVersion = 'n8.1.2-34-g9b6c8969e0-20260812'
    binaryVariant = 'win64-lgpl-shared'
    ffmpegCommit = '9b6c8969e05b4f0b29f0f85cd501be6b3e582e6b'
    buildScriptsCommit = '2a3249ec58228c661e7ff8fdc9ea997b18aa912b'
    verifiedArtifacts = $sources
    blockers = @(
        'Complete exact source archives for every statically linked LGPL-covered optional dependency are not yet bundled.',
        'A source-complete minimal FFmpeg rebuild is an acceptable alternative.'
    )
}
$utf8NoBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText(
    (Join-Path $outputFullPath 'FFMPEG_SOURCE_CLOSURE.json'),
    ($manifest | ConvertTo-Json -Depth 8) + "`n",
    $utf8NoBom
)
$manifest | ConvertTo-Json -Depth 8
