$ErrorActionPreference = 'Stop'

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$downloads = Join-Path $projectRoot '.tools\vlm-downloads'
$runtimeRoot = Join-Path $projectRoot '.tools\vlm-runtime'
$modelsRoot = Join-Path $projectRoot '.tools\vlm-models'
$licenseRoot = Join-Path $projectRoot 'audit\vlm-licenses'
$auditPath = Join-Path $projectRoot 'audit\VLM_COMPONENTS.json'
New-Item -ItemType Directory -Path $downloads, $runtimeRoot, $modelsRoot, $licenseRoot -Force | Out-Null

$components = @(
    [ordered]@{
        name = 'Qwen3VL-2B-Instruct-Q4_K_M.gguf'; kind = 'model'
        url = 'https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3VL-2B-Instruct-Q4_K_M.gguf?download=true'
        sha256 = '089d75c52f4b7ffc56ba998ffc50aae89fcafc755f9e7208aacca281dca6c2ae'; size = 1107409952
    },
    [ordered]@{
        name = 'mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf'; kind = 'projector'
        url = 'https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf?download=true'
        sha256 = 'f9a68fabba69c3b81e153367b2c7521030b0fa8bb0de400c9599c8e6725f9c82'; size = 445053216
    },
    [ordered]@{
        name = 'llama-b10405-bin-win-cpu-x64.zip'; kind = 'runtime-cpu'
        url = 'https://github.com/ggml-org/llama.cpp/releases/download/b10405/llama-b10405-bin-win-cpu-x64.zip'
        sha256 = '31f3bcc3f7645715b3ed8e845ab338d94659aa0e512b2211b8d94b9c8eb24758'; size = 18468077
    },
    [ordered]@{
        name = 'llama-b10405-bin-win-vulkan-x64.zip'; kind = 'runtime-vulkan'
        url = 'https://github.com/ggml-org/llama.cpp/releases/download/b10405/llama-b10405-bin-win-vulkan-x64.zip'
        sha256 = '54b51536350b5bc7d32f2882f8ce2f0e66fdcd65c434ca1c6e10cc6112113d77'; size = 34576307
    }
)

function Assert-Within([string]$Path, [string]$Parent) {
    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $resolvedParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    if (-not $resolvedPath.StartsWith($resolvedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside $Parent`: $Path"
    }
}

function Test-Hash([string]$Path, [string]$Expected) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.Equals(
        $Expected, [System.StringComparison]::OrdinalIgnoreCase)
}

function Receive-Pinned($Component) {
    $target = Join-Path $downloads $Component.name
    Assert-Within $target $downloads
    if (-not (Test-Hash $target $Component.sha256)) {
        if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
        Write-Host "DOWNLOAD=$($Component.name)"
        & curl.exe -L --fail --retry 4 --retry-delay 2 --output $target $Component.url
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $($Component.name)" }
    }
    if ((Get-Item -LiteralPath $target).Length -ne [int64]$Component.size) {
        throw "Pinned component size mismatch: $($Component.name)"
    }
    if (-not (Test-Hash $target $Component.sha256)) {
        throw "Pinned component hash mismatch: $($Component.name)"
    }
    return $target
}

foreach ($component in $components) {
    $source = Receive-Pinned $component
    if ($component.kind -in @('model', 'projector')) {
        Copy-Item -LiteralPath $source -Destination (Join-Path $modelsRoot $component.name) -Force
    }
}

foreach ($backend in @('cpu', 'vulkan')) {
    $component = $components | Where-Object { $_.kind -eq "runtime-$backend" }
    $archive = Join-Path $downloads $component.name
    $target = Join-Path $runtimeRoot $backend
    Assert-Within $target $runtimeRoot
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $target -Force
    $server = Get-ChildItem -LiteralPath $target -Recurse -Filter 'llama-server.exe' -File | Select-Object -First 1
    if ($null -eq $server) { throw "llama-server.exe missing from $($component.name)" }
    if ($server.Directory.FullName -ne $target) {
        Get-ChildItem -LiteralPath $server.Directory.FullName -Force | Copy-Item -Destination $target -Recurse -Force
    }
    & (Join-Path $target 'llama-server.exe') --version | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "llama-server $backend runtime cannot run" }
}

& curl.exe -L --fail --retry 3 --output (Join-Path $licenseRoot 'LICENSE-QWEN-APACHE-2.0.txt') `
    'https://raw.githubusercontent.com/QwenLM/Qwen3-VL/main/LICENSE'
if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve the Qwen license.' }
& curl.exe -L --fail --retry 3 --output (Join-Path $licenseRoot 'LICENSE-LLAMA.CPP-MIT.txt') `
    'https://raw.githubusercontent.com/ggml-org/llama.cpp/b10405/LICENSE'
if ($LASTEXITCODE -ne 0) { throw 'Unable to retrieve the llama.cpp license.' }

$audit = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString('o')
    model = 'Qwen/Qwen3-VL-2B-Instruct-GGUF'
    quantization = 'Q4_K_M'
    runtime = 'llama.cpp b10405'
    offlineAtRuntime = $true
    components = $components
}
[System.IO.File]::WriteAllText($auditPath, ($audit | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
Write-Host "VLM_READY=$modelsRoot"
