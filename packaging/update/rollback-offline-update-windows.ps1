param(
    [Parameter(Mandatory = $true)][string]$BackupId,
    [string]$PackageRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
if ($BackupId -notmatch '^[A-Za-z0-9._+-]{1,128}$') { throw 'Invalid rollback ID.' }
$root = [IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
$data = Join-Path $root 'data'
$backupRoot = [IO.Path]::GetFullPath((Join-Path $data ('updates\backups\' + $BackupId)))
$expectedBackups = [IO.Path]::GetFullPath((Join-Path $data 'updates\backups')).TrimEnd('\') + '\'
if (-not $backupRoot.StartsWith($expectedBackups, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath (Join-Path $backupRoot 'replaced-top-level.txt') -PathType Leaf)) {
    throw 'Rollback backup does not exist or escaped the package data directory.'
}
$java = Join-Path $root 'runtime\bin\java.exe'
$application = Join-Path $root 'app\doc-redaction-poc.jar'
& $java '-Dfile.encoding=UTF-8' -jar $application --assert-instance-stopped $data
if ($LASTEXITCODE -ne 0) { throw 'Stop the application before rollback.' }
$names = @(Get-Content -LiteralPath (Join-Path $backupRoot 'replaced-top-level.txt') -Encoding UTF8 |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$failedSnapshot = Join-Path $data ('updates\failed-current\' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Path $failedSnapshot -Force | Out-Null
$restored = New-Object System.Collections.Generic.List[string]
try {
    foreach ($name in $names) {
        if ($name -notmatch '^[A-Za-z0-9._-]{1,80}$' -or $name -ieq 'data') { throw 'Unsafe rollback target.' }
        $target = [IO.Path]::GetFullPath((Join-Path $root $name))
        if (-not $target.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Rollback target escaped package root.'
        }
        if (Test-Path -LiteralPath $target) {
            Move-Item -LiteralPath $target -Destination (Join-Path $failedSnapshot $name)
        }
        $previous = Join-Path $backupRoot ('payload\' + $name)
        if (Test-Path -LiteralPath $previous) {
            Move-Item -LiteralPath $previous -Destination $target
        }
        $restored.Add($name)
    }
    Write-Output "ROLLBACK_APPLIED=$BackupId"
    Write-Output "FAILED_VERSION_SNAPSHOT=$failedSnapshot"
} catch {
    $reverseRestored = @($restored.ToArray())
    [array]::Reverse($reverseRestored)
    foreach ($name in $reverseRestored) {
        $target = Join-Path $root $name
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
        $current = Join-Path $failedSnapshot $name
        if (Test-Path -LiteralPath $current) { Move-Item -LiteralPath $current -Destination $target }
    }
    throw
}
