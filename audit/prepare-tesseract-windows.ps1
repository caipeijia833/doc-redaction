$ErrorActionPreference = 'Stop'

# Builds the project-local Windows OCR runtime from an isolated MSYS2 UCRT64 tree.
# Nothing is installed into Program Files, PATH, the registry, or a Windows service.
$projectRoot = Split-Path $PSScriptRoot -Parent
$msysRoot = Join-Path $projectRoot '.tools\msys64'
$ucrtRoot = Join-Path $msysRoot 'ucrt64'
$bashExe = Join-Path $msysRoot 'usr\bin\bash.exe'
$bsdtarExe = Join-Path $msysRoot 'usr\bin\bsdtar.exe'
$sourceExe = Join-Path $ucrtRoot 'bin\tesseract.exe'
$sourceData = Join-Path $ucrtRoot 'share\tessdata'
$targetRoot = Join-Path $projectRoot '.tools\ocr-windows'
$cacheRoot = Join-Path $msysRoot 'var\cache\pacman\pkg'
$downloadRoot = Join-Path $projectRoot '.tools\downloads'

$expected = @{
    TesseractExe = '385b5c4ffab7ae33005eca6d3396bfaf6380bd37fe553debfff10f2d15c22a1b'
    ChiSim = 'a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730'
    Eng = '7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2'
    Osd = '9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff'
}

foreach ($required in @($bashExe, $bsdtarExe, $sourceExe,
        (Join-Path $sourceData 'chi_sim.traineddata'),
        (Join-Path $sourceData 'eng.traineddata'),
        (Join-Path $sourceData 'osd.traineddata'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required project-local OCR input is missing: $required"
    }
}

function Assert-Sha256([string]$Path, [string]$ExpectedHash) {
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $ExpectedHash) {
        throw "SHA-256 mismatch for $Path. Expected $ExpectedHash; found $actual"
    }
}

Assert-Sha256 $sourceExe $expected.TesseractExe
Assert-Sha256 (Join-Path $sourceData 'chi_sim.traineddata') $expected.ChiSim
Assert-Sha256 (Join-Path $sourceData 'eng.traineddata') $expected.Eng
Assert-Sha256 (Join-Path $sourceData 'osd.traineddata') $expected.Osd

$lddOutput = & $bashExe -lc 'PATH=/ucrt64/bin:/usr/bin ldd /ucrt64/bin/tesseract.exe'
if ($LASTEXITCODE -ne 0 -or ($lddOutput -match 'not found')) {
    throw 'Unable to resolve the Tesseract UCRT64 dependency closure.'
}
$dllNames = $lddOutput | ForEach-Object {
    if ($_ -match '=> /ucrt64/bin/([^ ]+)') { $Matches[1] }
} | Sort-Object -Unique
if ($dllNames.Count -lt 1) { throw 'No project-local Tesseract DLL dependencies were detected.' }

$resolvedTools = [System.IO.Path]::GetFullPath((Join-Path $projectRoot '.tools'))
$resolvedTarget = [System.IO.Path]::GetFullPath($targetRoot)
if (-not $resolvedTarget.StartsWith($resolvedTools + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to replace an OCR runtime outside the project .tools directory.'
}
if (Test-Path -LiteralPath $resolvedTarget) {
    Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
}

$targetBin = Join-Path $resolvedTarget 'bin'
$targetData = Join-Path $resolvedTarget 'share\tessdata'
$targetLicenses = Join-Path $resolvedTarget 'licenses'
New-Item -ItemType Directory -Force -Path $targetBin, $targetData, $targetLicenses | Out-Null
Copy-Item -LiteralPath $sourceExe -Destination $targetBin
foreach ($dllName in $dllNames) {
    Copy-Item -LiteralPath (Join-Path $ucrtRoot "bin\$dllName") -Destination $targetBin
}
Copy-Item -LiteralPath (Join-Path $sourceData 'configs') -Destination $targetData -Recurse
Copy-Item -LiteralPath (Join-Path $sourceData 'tessconfigs') -Destination $targetData -Recurse
foreach ($trainedData in @('chi_sim.traineddata', 'eng.traineddata', 'osd.traineddata')) {
    Copy-Item -LiteralPath (Join-Path $sourceData $trainedData) -Destination $targetData
}

# MSYS2 packages provide the upstream license files here. Copy the complete
# installed license set: extra notices are preferable to silently omitting a
# transitive native dependency notice.
$licenseSource = Join-Path $ucrtRoot 'share\licenses'
if (Test-Path -LiteralPath $licenseSource -PathType Container) {
    Get-ChildItem -LiteralPath $licenseSource -Directory | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $targetLicenses -Recurse
    }
}
$licenseSupplements = Join-Path $PSScriptRoot 'ocr-license-supplements'
if (Test-Path -LiteralPath $licenseSupplements -PathType Container) {
    Get-ChildItem -LiteralPath $licenseSupplements -Directory | ForEach-Object {
        $supplementTarget = Join-Path $targetLicenses $_.Name
        New-Item -ItemType Directory -Force -Path $supplementTarget | Out-Null
        Get-ChildItem -LiteralPath $_.FullName -File | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $supplementTarget -Force
        }
    }
}

$runtimePaths = @('/ucrt64/bin/tesseract.exe') + ($dllNames | ForEach-Object { "/ucrt64/bin/$_" }) + @(
    '/ucrt64/share/tessdata/chi_sim.traineddata',
    '/ucrt64/share/tessdata/eng.traineddata',
    '/ucrt64/share/tessdata/osd.traineddata'
)
$ownerCommand = 'pacman -Qqo ' + ($runtimePaths -join ' ')
$owners = (& $bashExe -lc $ownerCommand) | Sort-Object -Unique
if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve native package ownership.' }
$installedLines = & $bashExe -lc ('pacman -Q ' + ($owners -join ' '))
if ($LASTEXITCODE -ne 0) { throw 'Unable to resolve installed native package versions.' }
$installedVersions = @{}
foreach ($installedLine in $installedLines) {
    if ($installedLine -match '^(.+) ([^ ]+)$') { $installedVersions[$Matches[1]] = $Matches[2] }
}

$components = foreach ($owner in $owners) {
    if (-not $installedVersions.ContainsKey($owner)) {
        throw "Unable to resolve installed version for $owner"
    }
    $name = $owner
    $version = $installedVersions[$owner]
    $archive = @(Get-ChildItem -LiteralPath $cacheRoot, $downloadRoot -Filter "$name-$version-*.pkg.tar.zst" -File |
        Sort-Object FullName -Unique) | Select-Object -First 1
    $packageInfo = @()
    if ($archive) { $packageInfo = & $bsdtarExe -xOf $archive.FullName .PKGINFO }
    $license = ($packageInfo | Where-Object { $_ -like 'license = *' } | Select-Object -First 1) -replace '^license = ', ''
    $url = ($packageInfo | Where-Object { $_ -like 'url = *' } | Select-Object -First 1) -replace '^url = ', ''
    [ordered]@{
        name = $name
        version = $version
        licenseDeclared = $license
        sourceUrl = $url
        packageArchive = if ($archive) { $archive.Name } else { $null }
        packageArchiveSha256 = if ($archive) {
            (Get-FileHash -Algorithm SHA256 -LiteralPath $archive.FullName).Hash.ToLowerInvariant()
        } else { $null }
    }
}

$fileHashes = Get-ChildItem -LiteralPath $resolvedTarget -Recurse -File |
    Where-Object { $_.Name -notin @('OCR_COMPONENTS.json', 'SHA256SUMS.native.txt') } |
    Sort-Object FullName | ForEach-Object {
        [ordered]@{
            path = $_.FullName.Substring($resolvedTarget.Length).TrimStart('\').Replace('\', '/')
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
            bytes = $_.Length
        }
    }
$inventory = [ordered]@{
    schemaVersion = 1
    generatedUtc = '2026-08-12T00:00:00Z'
    platform = 'windows-x64-ucrt'
    tesseractVersion = '5.5.2'
    languages = @('chi_sim', 'eng', 'osd')
    buildSource = 'MSYS2 UCRT64 binary packages'
    components = @($components)
    files = @($fileHashes)
}
[System.IO.File]::WriteAllText((Join-Path $resolvedTarget 'OCR_COMPONENTS.json'),
    ($inventory | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))

$manifest = @('# SHA-256 manifest for the native OCR runtime') +
    (Get-ChildItem -LiteralPath $resolvedTarget -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $_.FullName.Substring($resolvedTarget.Length).TrimStart('\').Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    })
[System.IO.File]::WriteAllLines((Join-Path $resolvedTarget 'SHA256SUMS.native.txt'), $manifest,
    [System.Text.UTF8Encoding]::new($false))

$portableExe = Join-Path $targetBin 'tesseract.exe'
& $portableExe --list-langs --tessdata-dir $targetData
if ($LASTEXITCODE -ne 0) { throw 'Portable Tesseract capability verification failed.' }
Write-Host "OCR_RUNTIME_OK=$resolvedTarget"
