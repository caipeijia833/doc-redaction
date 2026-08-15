param(
    [Parameter(Mandatory = $true)][string]$PackageRoot,
    [int]$Port = 18765
)

$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
$application = Join-Path $root 'app\doc-redaction-poc.jar'
$launcherPath = Join-Path $root 'start-windows.bat'
$data = Join-Path $root 'data'
if ($root.Length -lt 10 -or -not (Test-Path -LiteralPath $application -PathType Leaf) -or
        -not (Test-Path -LiteralPath $launcherPath -PathType Leaf)) {
    throw 'Invalid package root.'
}
$logRoot = Join-Path $data 'diagnostics\package-smoke'
New-Item -ItemType Directory -Path $logRoot -Force | Out-Null
$stdout = Join-Path $logRoot 'server.out'
$stderr = Join-Path $logRoot 'server.err'
$command = '"' + $launcherPath + '" --no-open --port ' + $Port
$launcher = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d', '/c', $command) `
    -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
$javaProcess = $null
try {
    $health = $null
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        Start-Sleep -Seconds 1
        try {
            $health = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/health" `
                -Headers @{ Host = "127.0.0.1:$Port" } -TimeoutSec 2
            if ($health.StatusCode -eq 200) { break }
        } catch { }
        if ($launcher.HasExited) { break }
    }
    if ($null -eq $health -or $health.StatusCode -ne 200) {
        throw "Package server did not become ready. stdout=$([IO.File]::ReadAllText($stdout)) stderr=$([IO.File]::ReadAllText($stderr))"
    }
    $javaProcess = Get-CimInstance Win32_Process | Where-Object {
        $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$root*doc-redaction-poc.jar*" -and
        $_.CommandLine -like "*--port $Port*"
    } | Select-Object -First 1
    if ($null -eq $javaProcess) { throw 'Unable to attribute the package Java process.' }

    try {
        Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/jobs" `
            -Headers @{ Host = 'attacker.invalid' } -TimeoutSec 3 | Out-Null
        throw 'Host header attack was not rejected.'
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -ne 421) { throw }
    }

    $secondOut = Join-Path $logRoot 'second.out'
    $secondErr = Join-Path $logRoot 'second.err'
    $safeArguments = @('-jar', 'app\doc-redaction-poc.jar', '--data', 'data',
        '--no-open', '--port', [string]($Port + 1))
    $second = Start-Process -FilePath (Join-Path $root 'runtime\bin\java.exe') `
        -WorkingDirectory $root -ArgumentList $safeArguments -WindowStyle Hidden -Wait -PassThru `
        -RedirectStandardOutput $secondOut -RedirectStandardError $secondErr
    $secondText = [IO.File]::ReadAllText($secondOut) + [IO.File]::ReadAllText($secondErr)
    if ($second.ExitCode -eq 0 -or $secondText -notmatch 'still running') {
        throw "Second-instance lock check failed: exit=$($second.ExitCode) output=$secondText"
    }
    [ordered]@{
        status = 'passed'
        health = $health.Content
        javaPid = $javaProcess.ProcessId
        hostHeaderRejection = 421
        secondInstanceExit = $second.ExitCode
    } | ConvertTo-Json
} finally {
    if ($null -ne $javaProcess) {
        Stop-Process -Id $javaProcess.ProcessId -Force -ErrorAction SilentlyContinue
    }
    if (-not $launcher.HasExited) {
        [void]$launcher.WaitForExit(10000)
        if (-not $launcher.HasExited) {
            Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue
        }
    }
}

Start-Sleep -Seconds 1
$remaining = @(Get-CimInstance Win32_Process | Where-Object {
    $_.Name -eq 'java.exe' -and $_.CommandLine -like "*$root*doc-redaction-poc.jar*"
})
if ($remaining.Count -gt 0) {
    throw 'A package Java process remained after smoke-test cleanup.'
}
Write-Host 'PACKAGE_HTTP_SMOKE_PASS'
