# Explicit development setup: local llama.cpp only; no paid provider probes.
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$PcBaseUrl = 'http://127.0.0.1:8080'
$repoRoot = Split-Path $PSScriptRoot -Parent
$adbPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$health = Invoke-RestMethod "$PcBaseUrl/health" -TimeoutSec 5
if ($health.status -ne 'ok') { throw 'Local model is not ready' }
$models = Invoke-RestMethod "$PcBaseUrl/v1/models" -TimeoutSec 5
if ($models.data.Count -ne 1 -or $models.data[0].id -notmatch 'Qwen3.5.*9B') {
    throw 'Expected the deployed Qwen3.5 9B server; no automatic model restart'
}
Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        $env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
        $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    & $adbPath -s $Serial reverse tcp:8080 tcp:8080
    if ($LASTEXITCODE -ne 0) { throw 'ADB reverse failed' }
    foreach ($apk in @('app\build\outputs\apk\debug\app-debug.apk',
        'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')) {
        & $adbPath -s $Serial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'APK installation failed' }
    }
    $result = & $adbPath -s $Serial shell am instrument -w -r `
        -e class com.zero.v2.app.acceptance.LocalModelAcceptanceTest `
        -e configureLocal true -e localModelId local-qwen `
        -e localBaseUrl http://127.0.0.1:8080/v1 `
        com.zero.v2.test/com.zero.v2.app.acceptance.ProviderAcceptanceRunner
    $result | Write-Output
    if (($result -join "`n") -notmatch 'OK \(1 test\)') { throw 'Local configuration/verification failed' }
    & $adbPath -s $Serial shell am start -n com.zero.v2/com.zero.v2.app.MainActivity
} finally { Pop-Location }
