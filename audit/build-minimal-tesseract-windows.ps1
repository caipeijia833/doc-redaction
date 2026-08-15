[CmdletBinding()]
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$BuildRoot,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
if (-not $BuildRoot) {
    $BuildRoot = Join-Path $ProjectRoot '.tools\ocr-minimal-build'
}
$BuildRoot = [System.IO.Path]::GetFullPath($BuildRoot)
$manifestRoot = Join-Path $ProjectRoot 'audit\ocr-build'
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere)) {
    throw 'Visual Studio Installer vswhere.exe not found.'
}
$vsPath = (& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath).Trim()
if (-not $vsPath) {
    throw 'Visual Studio 2022 C++ Build Tools not found.'
}
$vsDevCmd = Join-Path $vsPath 'Common7\Tools\VsDevCmd.bat'
$cmake = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$ninja = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe'
foreach ($tool in @($vsDevCmd, $cmake, $ninja)) {
    if (-not (Test-Path -LiteralPath $tool)) {
        throw "Required build tool not found: $tool"
    }
}

function Import-VsEnvironment {
    param([string]$CommandFile)
    $lines = & cmd.exe /s /c "`"$CommandFile`" -no_logo -arch=x64 -host_arch=x64 && set"
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to initialize the Visual Studio build environment.'
    }
    foreach ($line in $lines) {
        $index = $line.IndexOf('=')
        if ($index -gt 0) {
            [Environment]::SetEnvironmentVariable($line.Substring(0, $index), $line.Substring($index + 1), 'Process')
        }
    }
}

function Ensure-PinnedRepository {
    param(
        [string]$Url,
        [string]$Commit,
        [string]$Target
    )
    $repositoryExists = Test-Path -LiteralPath (Join-Path $Target '.git')
    if (-not $repositoryExists) {
        if (Test-Path -LiteralPath $Target) {
            throw "Refusing to overwrite non-repository path: $Target"
        }
        & git clone --filter=blob:none --no-checkout $Url $Target
        if ($LASTEXITCODE -ne 0) { throw "git clone failed: $Url" }
    }
    $actualOutput = & git -C $Target rev-parse HEAD 2>$null
    $actual = if ($LASTEXITCODE -eq 0 -and $actualOutput) { $actualOutput.Trim() } else { '' }
    if ($actual -eq $Commit) {
        & git -C $Target diff --quiet --ignore-submodules --
        if ($LASTEXITCODE -ne 0) { throw "Pinned source has uncommitted changes: $Target" }
        & git -C $Target diff --cached --quiet --ignore-submodules --
        if ($LASTEXITCODE -ne 0) { throw "Pinned source has staged changes: $Target" }
        return
    }
    & git -C $Target cat-file -e "$Commit^{commit}" 2>$null
    if ($LASTEXITCODE -eq 0) {
        & git -C $Target checkout --detach $Commit
        if ($LASTEXITCODE -ne 0) { throw "git checkout failed: $Url@$Commit" }
    } else {
        & git -C $Target fetch --depth 1 origin $Commit
        if ($LASTEXITCODE -ne 0) { throw "git fetch failed: $Url@$Commit" }
        & git -C $Target checkout --detach $Commit
        if ($LASTEXITCODE -ne 0) { throw "git checkout failed: $Url@$Commit" }
    }
    $actual = (& git -C $Target rev-parse HEAD).Trim()
    if ($actual -ne $Commit) {
        throw "Pinned source mismatch: expected=$Commit actual=$actual"
    }
    & git -C $Target diff --quiet --ignore-submodules --
    if ($LASTEXITCODE -ne 0) { throw "Pinned source has uncommitted changes: $Target" }
    & git -C $Target diff --cached --quiet --ignore-submodules --
    if ($LASTEXITCODE -ne 0) { throw "Pinned source has staged changes: $Target" }
}

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed ($LASTEXITCODE): $Program $($Arguments -join ' ')"
    }
}

if ($Clean -and (Test-Path -LiteralPath $BuildRoot)) {
    $resolved = [System.IO.Path]::GetFullPath($BuildRoot)
    $allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot '.tools'))
    if ($resolved -eq $allowedRoot -or -not $resolved.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean path outside project .tools: $resolved"
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
Import-VsEnvironment -CommandFile $vsDevCmd
$env:VCPKG_DISABLE_METRICS = '1'
Remove-Item Env:VCPKG_ROOT -ErrorAction SilentlyContinue

$vcpkgCommit = '9e593bb18ea69cc5095e012465dcd675a822ed0d'
$tesseractCommit = '6e1d56a847e697de07b38619356550e5cf4e8633'
$leptonicaCommit = '13275a278eb55b5746e33f95fbf5a2c8f604b3ab'
$tessdataCommit = '65727574dfcd264acbb0c3e07860e4e9e9b22185'

$sourceRoot = Join-Path $BuildRoot 'src'
$vcpkgRoot = Join-Path $sourceRoot 'vcpkg'
$tesseractSource = Join-Path $sourceRoot 'tesseract'
$leptonicaSource = Join-Path $sourceRoot 'leptonica'
$tessdataSource = Join-Path $sourceRoot 'tessdata_fast'
Ensure-PinnedRepository 'https://github.com/microsoft/vcpkg.git' $vcpkgCommit $vcpkgRoot
Ensure-PinnedRepository 'https://github.com/tesseract-ocr/tesseract.git' $tesseractCommit $tesseractSource
Ensure-PinnedRepository 'https://github.com/DanBloomberg/leptonica.git' $leptonicaCommit $leptonicaSource
Ensure-PinnedRepository 'https://github.com/tesseract-ocr/tessdata_fast.git' $tessdataCommit $tessdataSource

$vcpkg = Join-Path $vcpkgRoot 'vcpkg.exe'
if (-not (Test-Path -LiteralPath $vcpkg)) {
    Invoke-Checked (Join-Path $vcpkgRoot 'bootstrap-vcpkg.bat') @('-disableMetrics')
}
$vcpkgInstalled = Join-Path $BuildRoot 'vcpkg-installed'
Invoke-Checked $vcpkg @(
    'install', '--triplet', 'x64-windows-static',
    "--x-manifest-root=$manifestRoot", "--x-install-root=$vcpkgInstalled",
    '--clean-after-build', '--disable-metrics'
)

$toolchain = Join-Path $vcpkgRoot 'scripts\buildsystems\vcpkg.cmake'
$leptonicaBuild = Join-Path $BuildRoot 'build\leptonica'
$leptonicaInstall = Join-Path $BuildRoot 'install\leptonica'
Invoke-Checked $cmake @(
    '-S', $leptonicaSource, '-B', $leptonicaBuild, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", '-DCMAKE_BUILD_TYPE=Release',
    "-DCMAKE_INSTALL_PREFIX=$leptonicaInstall", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    "-DVCPKG_INSTALLED_DIR=$vcpkgInstalled", '-DVCPKG_TARGET_TRIPLET=x64-windows-static',
    '-DVCPKG_MANIFEST_MODE=OFF',
    '-DCMAKE_POLICY_DEFAULT_CMP0091=NEW', '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded',
    '-DBUILD_SHARED_LIBS=OFF',
    '-DSW_BUILD=OFF', '-DBUILD_PROG=OFF', '-DSTRICT_CONF=ON',
    '-DENABLE_ZLIB=ON', '-DENABLE_PNG=ON', '-DENABLE_GIF=OFF',
    '-DENABLE_JPEG=OFF', '-DENABLE_TIFF=OFF', '-DENABLE_WEBP=OFF',
    '-DENABLE_OPENJPEG=OFF'
)
Invoke-Checked $cmake @('--build', $leptonicaBuild, '--target', 'install', '--parallel')

$tesseractBuild = Join-Path $BuildRoot 'build\tesseract'
$tesseractInstall = Join-Path $BuildRoot 'install\tesseract'
$leptonicaConfig = Join-Path $leptonicaInstall 'lib\cmake\leptonica'
Invoke-Checked $cmake @(
    '-S', $tesseractSource, '-B', $tesseractBuild, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", '-DCMAKE_BUILD_TYPE=Release',
    "-DCMAKE_INSTALL_PREFIX=$tesseractInstall", "-DCMAKE_TOOLCHAIN_FILE=$toolchain",
    "-DVCPKG_INSTALLED_DIR=$vcpkgInstalled", '-DVCPKG_TARGET_TRIPLET=x64-windows-static',
    '-DVCPKG_MANIFEST_MODE=OFF',
    "-DLeptonica_DIR=$leptonicaConfig", "-DCMAKE_PREFIX_PATH=$leptonicaInstall",
    '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded', '-DWIN32_MT_BUILD=ON',
    '-DBUILD_SHARED_LIBS=OFF', '-DSW_BUILD=OFF', '-DOPENMP_BUILD=OFF',
    '-DGRAPHICS_DISABLED=ON', '-DDISABLED_LEGACY_ENGINE=OFF',
    '-DBUILD_TRAINING_TOOLS=OFF', '-DBUILD_TESTS=OFF', '-DUSE_SYSTEM_ICU=OFF',
    '-DDISABLE_TIFF=ON', '-DDISABLE_ARCHIVE=ON', '-DDISABLE_CURL=ON',
    '-DINSTALL_CONFIGS=OFF', '-DENABLE_NATIVE=OFF', '-DENABLE_LTO=OFF',
    '-DENABLE_PRECOMPILED_HEADERS=OFF', '-DENABLE_CCACHE=OFF'
)
Invoke-Checked $cmake @('--build', $tesseractBuild, '--target', 'install', '--parallel')

$runtime = Join-Path $BuildRoot 'runtime'
$runtimeResolved = [System.IO.Path]::GetFullPath($runtime)
$buildRootResolved = [System.IO.Path]::GetFullPath($BuildRoot).TrimEnd('\') + '\'
if (-not $runtimeResolved.StartsWith($buildRootResolved, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace runtime outside build root: $runtimeResolved"
}
if (Test-Path -LiteralPath $runtimeResolved) {
    Remove-Item -LiteralPath $runtimeResolved -Recurse -Force
}
$bin = Join-Path $runtime 'bin'
$data = Join-Path $runtime 'share\tessdata'
$licenses = Join-Path $runtime 'licenses'
New-Item -ItemType Directory -Force -Path $bin, $data, $licenses | Out-Null
$tesseractExe = Get-ChildItem -Path $tesseractInstall -Recurse -Filter tesseract.exe | Select-Object -First 1
if (-not $tesseractExe) { throw 'Built tesseract.exe not found.' }
Copy-Item -LiteralPath $tesseractExe.FullName -Destination (Join-Path $bin 'tesseract.exe') -Force
foreach ($language in @('chi_sim', 'eng', 'osd')) {
    Copy-Item -LiteralPath (Join-Path $tessdataSource "$language.traineddata") -Destination $data -Force
}
Copy-Item -LiteralPath (Join-Path $tesseractSource 'tessdata\configs') -Destination $data -Recurse -Force
Copy-Item -LiteralPath (Join-Path $tesseractSource 'LICENSE') -Destination (Join-Path $licenses 'TESSERACT-APACHE-2.0.txt') -Force
Copy-Item -LiteralPath (Join-Path $leptonicaSource 'leptonica-license.txt') -Destination (Join-Path $licenses 'LEPTONICA-BSD-2-CLAUSE.txt') -Force
Copy-Item -LiteralPath (Join-Path $vcpkgInstalled 'x64-windows-static\share\libpng\copyright') -Destination (Join-Path $licenses 'LIBPNG.txt') -Force
Copy-Item -LiteralPath (Join-Path $vcpkgInstalled 'x64-windows-static\share\zlib\copyright') -Destination (Join-Path $licenses 'ZLIB.txt') -Force

$dumpbin = Get-ChildItem -Path (Join-Path $vsPath 'VC\Tools\MSVC') -Recurse -Filter dumpbin.exe |
    Where-Object FullName -Match '\\Hostx64\\x64\\dumpbin.exe$' | Select-Object -First 1
if (-not $dumpbin) { throw 'dumpbin.exe not found.' }
$dependents = (& $dumpbin.FullName /nologo /dependents (Join-Path $bin 'tesseract.exe')) -join "`n"
$dependentDlls = [regex]::Matches($dependents, '(?im)^\s+([A-Za-z0-9._-]+\.dll)\s*$') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$forbidden = @('libgcc', 'libstdc++', 'libwinpthread', 'libarchive', 'libcurl', 'libiconv', 'libintl', 'libunistring')
foreach ($name in $forbidden) {
    if ($dependents -match [regex]::Escape($name)) {
        throw "Forbidden native dependency detected: $name"
    }
}
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$version = (& (Join-Path $bin 'tesseract.exe') --version 2>&1 | ForEach-Object { $_.ToString() }) -join "`n"
$versionExit = $LASTEXITCODE
$languages = (& (Join-Path $bin 'tesseract.exe') --list-langs --tessdata-dir $data 2>&1 |
    ForEach-Object { $_.ToString() }) -join "`n"
$languagesExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorAction
if ($versionExit -ne 0 -or $languagesExit -ne 0) {
    throw "OCR executable self-test failed: versionExit=$versionExit languagesExit=$languagesExit"
}
foreach ($language in @('chi_sim', 'eng', 'osd')) {
    if ($languages -notmatch "(?m)^$([regex]::Escape($language))$") {
        throw "OCR language validation failed: $language"
    }
}

$metadata = [ordered]@{
    schemaVersion = 1
    architecture = 'windows-x64'
    linkage = 'static'
    msvcRuntime = 'static-MT'
    tesseract = [ordered]@{ version = '5.5.2'; commit = $tesseractCommit; license = 'Apache-2.0' }
    leptonica = [ordered]@{ version = '1.87.0'; commit = $leptonicaCommit; license = 'BSD-2-Clause' }
    tessdataFast = [ordered]@{ tag = '4.1.0'; commit = $tessdataCommit; license = 'Apache-2.0' }
    vcpkg = [ordered]@{ tag = '2026.07.29'; commit = $vcpkgCommit; triplet = 'x64-windows-static' }
    enabledImageCodecs = @('png', 'zlib')
    disabledFeatures = @('curl', 'libarchive', 'tiff', 'gif', 'jpeg', 'webp', 'openjpeg', 'openmp', 'training-tools', 'lto')
    dependentDlls = @($dependentDlls)
    verifiedLanguages = @('chi_sim', 'eng', 'osd')
    tesseractSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $bin 'tesseract.exe')).Hash.ToLowerInvariant()
}
[System.IO.File]::WriteAllText((Join-Path $runtime 'OCR_COMPONENTS.json'),
    ($metadata | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))

$runtimePrefix = $runtime.TrimEnd('\') + '\'
$hashLines = Get-ChildItem -Path $runtime -Recurse -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.native.txt' } |
    Sort-Object FullName | ForEach-Object {
        if (-not $_.FullName.StartsWith($runtimePrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Runtime file escaped expected root: $($_.FullName)"
        }
        $relative = $_.FullName.Substring($runtimePrefix.Length).Replace('\', '/')
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
        "$hash  $relative"
    }
[System.IO.File]::WriteAllLines((Join-Path $runtime 'SHA256SUMS.native.txt'), $hashLines,
    [System.Text.UTF8Encoding]::new($false))

Write-Output "MINIMAL_OCR_RUNTIME=$runtime"
Write-Output "TESSERACT_SHA256=$((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $bin 'tesseract.exe')).Hash.ToLowerInvariant())"
