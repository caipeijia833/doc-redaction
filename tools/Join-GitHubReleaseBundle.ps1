[CmdletBinding()]
param(
    [string]$BundleDirectory = $PSScriptRoot,
    [string]$OutputFile
)

$ErrorActionPreference = 'Stop'
$bundleRoot = (Resolve-Path -LiteralPath $BundleDirectory).Path
$manifestPath = Join-Path $bundleRoot 'RELEASE_ASSETS.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json

if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path $bundleRoot ([string]$manifest.originalFile)
}
$outputFullPath = [IO.Path]::GetFullPath($OutputFile)

foreach ($part in $manifest.parts) {
    $partName = [string]$part.file
    if ([IO.Path]::GetFileName($partName) -ne $partName) {
        throw "Unsafe part name in manifest: $partName"
    }
    $partPath = Join-Path $bundleRoot $partName
    $partItem = Get-Item -LiteralPath $partPath -ErrorAction Stop
    if ($partItem.Length -ne [long]$part.bytes) {
        throw "Part size mismatch: $partName"
    }
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $partPath).Hash.ToLowerInvariant()
    if ($actualHash -ne ([string]$part.sha256).ToLowerInvariant()) {
        throw "Part SHA-256 mismatch: $partName"
    }
}

if (Test-Path -LiteralPath $outputFullPath) {
    $existing = Get-Item -LiteralPath $outputFullPath
    $existingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $existing.FullName).Hash.ToLowerInvariant()
    if ($existing.Length -eq [long]$manifest.originalBytes -and $existingHash -eq ([string]$manifest.originalSha256).ToLowerInvariant()) {
        Write-Host "ALREADY_OK=$outputFullPath"
        exit 0
    }
    throw "Output already exists but does not match the release manifest: $outputFullPath"
}

$partialPath = "$outputFullPath.partial"
if (Test-Path -LiteralPath $partialPath) {
    throw "Partial output already exists. Inspect or remove it before retrying: $partialPath"
}

$buffer = New-Object byte[] (8MB)
$outputStream = [IO.File]::Open($partialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    foreach ($part in ($manifest.parts | Sort-Object order)) {
        $partPath = Join-Path $bundleRoot ([string]$part.file)
        $inputStream = [IO.File]::Open($partPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try {
            while (($read = $inputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
                $outputStream.Write($buffer, 0, $read)
            }
        } finally {
            $inputStream.Dispose()
        }
    }
    $outputStream.Flush()
} finally {
    $outputStream.Dispose()
}

$partialItem = Get-Item -LiteralPath $partialPath
$partialHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $partialPath).Hash.ToLowerInvariant()
if ($partialItem.Length -ne [long]$manifest.originalBytes -or
        $partialHash -ne ([string]$manifest.originalSha256).ToLowerInvariant()) {
    Remove-Item -LiteralPath $partialPath -Force
    throw 'Reassembled file failed size or SHA-256 validation; invalid partial output was removed.'
}

Move-Item -LiteralPath $partialPath -Destination $outputFullPath
Write-Host "ASSEMBLE_OK=$outputFullPath"
Write-Host "SHA256=$partialHash"
