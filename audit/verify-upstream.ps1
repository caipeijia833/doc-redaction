[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$lockPath = Join-Path $PSScriptRoot 'upstream-lock.json'
$lock = Get-Content -LiteralPath $lockPath -Raw -Encoding utf8 | ConvertFrom-Json
$failures = [System.Collections.Generic.List[string]]::new()
$results = foreach ($repo in $lock.repositories) {
    $repoPath = Join-Path (Join-Path $WorkspaceRoot 'upstream') $repo.name
    if (-not (Test-Path -LiteralPath $repoPath -PathType Container)) {
        $failures.Add("missing repository: $($repo.name)")
        continue
    }

    $actualRemote = (git -C $repoPath remote get-url origin).Trim()
    $actualCommit = (git -C $repoPath rev-parse HEAD).Trim()
    $actualTree = (git -C $repoPath rev-parse 'HEAD^{tree}').Trim()
    $status = (git -C $repoPath status --porcelain) -join ''
    $licenseRelative = if ($repo.license_actual_path) {
        $repo.license_actual_path
    } else {
        $repo.license_file
    }
    $licensePath = Join-Path $repoPath $licenseRelative
    $actualLicenseHash = if (Test-Path -LiteralPath $licensePath -PathType Leaf) {
        (Get-FileHash -LiteralPath $licensePath -Algorithm SHA256).Hash
    } else {
        '(missing)'
    }

    $checks = [ordered]@{
        remote = $actualRemote -eq $repo.remote
        commit = $actualCommit -eq $repo.commit
        tree = $actualTree -eq $repo.tree
        license = $actualLicenseHash -eq $repo.license_sha256
        clean = [string]::IsNullOrWhiteSpace($status)
    }

    foreach ($entry in $checks.GetEnumerator()) {
        if (-not $entry.Value) {
            $failures.Add("$($repo.name): $($entry.Key) mismatch")
        }
    }

    [pscustomobject]@{
        repository = $repo.name
        decision = $repo.decision
        remote_ok = $checks.remote
        commit_ok = $checks.commit
        tree_ok = $checks.tree
        license_ok = $checks.license
        clean = $checks.clean
    }
}

$results | Format-Table -AutoSize
if ($failures.Count -gt 0) {
    Write-Error ("Upstream verification failed:`n- " + ($failures -join "`n- "))
}

Write-Output 'UPSTREAM_VERIFICATION_OK'
