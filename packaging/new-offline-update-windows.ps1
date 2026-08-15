param(
    [Parameter(Mandatory = $true)][string]$PackageRoot,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$KeyId,
    [Parameter(Mandatory = $true)][string]$PrivateKeyPath,
    [Parameter(Mandatory = $true)][string]$OutputArchive
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
$privateKey = [IO.Path]::GetFullPath($PrivateKeyPath)
$output = [IO.Path]::GetFullPath($OutputArchive)
if ($Version -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]{0,63}$') { throw 'Invalid update version.' }
if ($KeyId -notmatch '^[A-Za-z0-9._-]{1,80}$') { throw 'Invalid update key ID.' }
if (-not (Test-Path -LiteralPath (Join-Path $root 'app\doc-redaction-poc.jar') -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $root 'runtime\bin\java.exe') -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $root 'runtime\bin\jar.exe') -PathType Leaf)) {
    throw 'Package root is missing the application or update-capable Java runtime.'
}
if (-not (Test-Path -LiteralPath $privateKey -PathType Leaf)) { throw 'Private signing key does not exist.' }
if ($privateKey.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The private update key must never be stored inside the distributable package.'
}
if (Test-Path -LiteralPath $output) { throw 'Output update archive already exists.' }

$outputParent = Split-Path -Parent $output
New-Item -ItemType Directory -Path $outputParent -Force | Out-Null
$work = Join-Path $outputParent ('update-work-' + [Guid]::NewGuid().ToString('N'))
$payload = Join-Path $work 'payload'
$allowed = @('app', 'runtime', 'ocr', 'media', 'vlm', 'repair', 'samples', 'update',
    'start-windows.bat', 'preflight-windows.ps1', 'start-macos.command', 'preflight-macos.sh',
    'README.md', 'SBOM.cdx.json', 'THIRD_PARTY_NOTICES.md', 'SHA256SUMS.txt', 'VERSION', 'DATA_SCHEMA_VERSION')
try {
    New-Item -ItemType Directory -Path $payload -Force | Out-Null
    foreach ($name in $allowed) {
        $source = Join-Path $root $name
        if (Test-Path -LiteralPath $source) {
            Copy-Item -LiteralPath $source -Destination (Join-Path $payload $name) -Recurse -Force
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $payload 'VERSION') -PathType Leaf) -or
            -not (Test-Path -LiteralPath (Join-Path $payload 'DATA_SCHEMA_VERSION') -PathType Leaf)) {
        throw 'Package must contain VERSION and DATA_SCHEMA_VERSION.'
    }
    $schemaVersion = [int]([IO.File]::ReadAllText((Join-Path $payload 'DATA_SCHEMA_VERSION')).Trim())
    if ($schemaVersion -ne 1) { throw 'This update builder currently supports only data schema version 1.' }
    $files = @(Get-ChildItem -LiteralPath $payload -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($payload.Length).TrimStart('\').Replace('\', '/')
        [ordered]@{
            path = $relative
            size = [int64]$_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
    if ($files.Count -eq 0 -or $files.Count -gt 50000) { throw 'Unsafe update file count.' }
    $manifestObject = [ordered]@{
        formatVersion = 1
        product = 'doc-redaction'
        version = $Version
        dataSchemaVersion = $schemaVersion
        keyId = $KeyId
        generatedAt = [DateTime]::UtcNow.ToString('o')
        files = $files
    }
    $manifest = Join-Path $work 'manifest.json'
    [IO.File]::WriteAllText($manifest, ($manifestObject | ConvertTo-Json -Depth 6 -Compress),
        [Text.UTF8Encoding]::new($false))
    $signature = Join-Path $work 'manifest.sig'
    $java = Join-Path $root 'runtime\bin\java.exe'
    $application = Join-Path $root 'app\doc-redaction-poc.jar'
    & $java '-Dfile.encoding=UTF-8' -jar $application --sign-update $manifest $privateKey $signature
    if ($LASTEXITCODE -ne 0) { throw 'Update signing failed.' }
    $trustedKeys = Join-Path $root 'update\trusted-public-keys.properties'
    & $java '-Dfile.encoding=UTF-8' -jar $application --verify-update $manifest $signature $trustedKeys $payload
    if ($LASTEXITCODE -ne 0) { throw 'The newly signed update failed self-verification.' }
    $jar = Join-Path $root 'runtime\bin\jar.exe'
    & $jar --create --file $output --no-manifest -C $work manifest.json -C $work manifest.sig -C $work payload
    if ($LASTEXITCODE -ne 0) { throw 'Update archive creation failed.' }
    $hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($output + '.sha256', $hash + "`n", [Text.Encoding]::ASCII)
    Write-Host "UPDATE_CREATED=$output"
    Write-Host "SHA256=$hash"
} finally {
    $resolvedWork = [IO.Path]::GetFullPath($work)
    if ((Test-Path -LiteralPath $resolvedWork) -and
            $resolvedWork.StartsWith([IO.Path]::GetFullPath($outputParent).TrimEnd('\') + '\',
                [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
