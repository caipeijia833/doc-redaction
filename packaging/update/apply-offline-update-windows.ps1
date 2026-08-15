param(
    [Parameter(Mandatory = $true)][string]$UpdateArchive,
    [string]$PackageRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
$archive = [IO.Path]::GetFullPath($UpdateArchive)
$data = Join-Path $root 'data'
$updates = Join-Path $data 'updates'
$java = Join-Path $root 'runtime\bin\java.exe'
$jar = Join-Path $root 'runtime\bin\jar.exe'
$application = Join-Path $root 'app\doc-redaction-poc.jar'
$trustedKeys = Join-Path $root 'update\trusted-public-keys.properties'
if ($root.Length -lt 10 -or -not (Test-Path -LiteralPath $application -PathType Leaf) -or
        -not (Test-Path -LiteralPath $java -PathType Leaf) -or
        -not (Test-Path -LiteralPath $jar -PathType Leaf) -or
        -not (Test-Path -LiteralPath $data -PathType Container)) {
    throw 'Package root is invalid or incomplete.'
}
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) { throw 'Update archive does not exist.' }

& $java '-Dfile.encoding=UTF-8' -jar $application --assert-instance-stopped $data
if ($LASTEXITCODE -ne 0) { throw 'Stop the application before applying an update.' }
New-Item -ItemType Directory -Path $updates -Force | Out-Null
$stage = Join-Path $updates ('staging-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
$applied = New-Object System.Collections.Generic.List[string]
$backupRoot = $null
try {
    $entries = @(& $jar --list --file $archive)
    if ($LASTEXITCODE -ne 0 -or $entries.Count -eq 0) { throw 'Update archive cannot be listed.' }
    foreach ($entry in $entries) {
        $normalized = $entry.Replace('\', '/')
        if ($normalized.StartsWith('/') -or $normalized.Contains(':') -or
                $normalized -match '(^|/)\.\.(/|$)' -or
                ($normalized -ne 'manifest.json' -and $normalized -ne 'manifest.sig' -and
                 $normalized -notmatch '^payload(/|$)')) {
            throw "Unsafe update archive entry: $entry"
        }
    }
    Push-Location $stage
    try { & $jar --extract --file $archive } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw 'Update archive extraction failed.' }
    $manifest = Join-Path $stage 'manifest.json'
    $signature = Join-Path $stage 'manifest.sig'
    $payload = Join-Path $stage 'payload'
    & $java '-Dfile.encoding=UTF-8' -jar $application --verify-update $manifest $signature $trustedKeys $payload
    if ($LASTEXITCODE -ne 0) { throw 'Update signature or payload verification failed.' }
    $metadata = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    $currentSchema = [int]([IO.File]::ReadAllText((Join-Path $root 'DATA_SCHEMA_VERSION')).Trim())
    if ([int]$metadata.dataSchemaVersion -ne $currentSchema) {
        throw 'Data schema migration is not supported by this updater; update refused.'
    }
    $backupId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-before-' + $metadata.version
    $backupRoot = Join-Path $updates ('backups\' + $backupId)
    $backupPayload = Join-Path $backupRoot 'payload'
    New-Item -ItemType Directory -Path $backupPayload -Force | Out-Null
    $topEntries = @(Get-ChildItem -LiteralPath $payload -Force | Select-Object -ExpandProperty Name)
    [IO.File]::WriteAllLines((Join-Path $backupRoot 'replaced-top-level.txt'), $topEntries,
        [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $backupRoot 'state.json'),
        ([ordered]@{ backupId = $backupId; targetVersion = $metadata.version; appliedAt = [DateTime]::UtcNow.ToString('o') } |
            ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    foreach ($name in $topEntries) {
        if ($name -notmatch '^[A-Za-z0-9._-]{1,80}$' -or $name -ieq 'data') {
            throw "Unsafe top-level update target: $name"
        }
        $target = [IO.Path]::GetFullPath((Join-Path $root $name))
        if (-not $target.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Update target escaped package root: $name"
        }
        if (Test-Path -LiteralPath $target) {
            Move-Item -LiteralPath $target -Destination (Join-Path $backupPayload $name)
        }
        $applied.Add($name)
        Move-Item -LiteralPath (Join-Path $payload $name) -Destination $target
    }
    Write-Output "UPDATE_APPLIED=$($metadata.version)"
    Write-Output "ROLLBACK_ID=$backupId"
} catch {
    if ($backupRoot -and (Test-Path -LiteralPath $backupRoot)) {
        $backupPayload = Join-Path $backupRoot 'payload'
        $reverseApplied = @($applied.ToArray())
        [array]::Reverse($reverseApplied)
        foreach ($name in $reverseApplied) {
            $target = Join-Path $root $name
            if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
            $previous = Join-Path $backupPayload $name
            if (Test-Path -LiteralPath $previous) { Move-Item -LiteralPath $previous -Destination $target }
        }
    }
    throw
} finally {
    $resolvedStage = [IO.Path]::GetFullPath($stage)
    if ((Test-Path -LiteralPath $resolvedStage) -and
            $resolvedStage.StartsWith([IO.Path]::GetFullPath($updates).TrimEnd('\') + '\',
                [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedStage -Recurse -Force
    }
}
