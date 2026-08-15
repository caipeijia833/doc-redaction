$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools\security-gate'
$gitleaksVersion = '8.30.0'
$gitleaksArchiveHash = '54fe94f644b832dd08e8c3a5915efb3bfa862386d59fb27ca0792cb687a83573'
$osvVersion = '2.4.0'
$osvHash = '0cdd113610126d5dfd5e12ad0e0b4f3e879291ff19bb43b0c52ed2f2c2df1a37'

function Get-VerifiedFile([string]$Uri, [string]$Destination, [string]$ExpectedHash) {
    if (-not (Test-Path -LiteralPath $Destination -PathType Leaf)) {
        Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $Destination
    }
    $actual = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedHash) {
        throw "Downloaded security tool failed SHA-256 verification: $Destination"
    }
}

New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
$gitleaksArchive = Join-Path $toolRoot "gitleaks_${gitleaksVersion}_windows_x64.zip"
Get-VerifiedFile `
    "https://github.com/gitleaks/gitleaks/releases/download/v$gitleaksVersion/gitleaks_${gitleaksVersion}_windows_x64.zip" `
    $gitleaksArchive $gitleaksArchiveHash
$gitleaksRoot = Join-Path $toolRoot "gitleaks-$gitleaksVersion"
$gitleaksExe = Join-Path $gitleaksRoot 'gitleaks.exe'
if (-not (Test-Path -LiteralPath $gitleaksExe -PathType Leaf)) {
    New-Item -ItemType Directory -Path $gitleaksRoot -Force | Out-Null
    Expand-Archive -LiteralPath $gitleaksArchive -DestinationPath $gitleaksRoot -Force
}
if ((& $gitleaksExe version).Trim() -ne $gitleaksVersion) {
    throw 'The prepared Gitleaks executable reported an unexpected version.'
}

$osvExe = Join-Path $toolRoot "osv-scanner-$osvVersion.exe"
Get-VerifiedFile `
    "https://github.com/google/osv-scanner/releases/download/v$osvVersion/osv-scanner_windows_amd64.exe" `
    $osvExe $osvHash
$osvVersionOutput = (& $osvExe --version | Out-String)
if ($osvVersionOutput -notmatch [regex]::Escape("osv-scanner version: $osvVersion")) {
    throw 'The prepared OSV-Scanner executable reported an unexpected version.'
}

Write-Host "SECURITY_TOOLS_READY gitleaks=$gitleaksVersion osv-scanner=$osvVersion"
