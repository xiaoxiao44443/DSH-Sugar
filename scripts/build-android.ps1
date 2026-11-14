[CmdletBinding()]
param(
    [string]$SucruneRoot = (Join-Path $PSScriptRoot '../../Sucrune'),
    [string]$SucruneApk,
    [string]$Tarballs,
    [string]$PythonProfile,
    [string]$Python = 'python',
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Gradle,
    [string]$ReadOnlyGradleCache,
    [switch]$SkipGuestPreparation,
    [switch]$Offline,
    [string]$Zig = $(if ($env:ZIG) { $env:ZIG } else { 'zig' })
)

$ErrorActionPreference = 'Stop'
$projectDir = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$SucruneRoot = (Resolve-Path -LiteralPath $SucruneRoot).Path
if (-not $SucruneApk) {
    $SucruneApk = Join-Path $SucruneRoot 'android/probeapp/build/outputs/apk/validation/probeapp-validation.apk'
}
if (-not (Test-Path -LiteralPath $SucruneApk -PathType Leaf)) {
    throw "Build the matching Sucrune source with its scripts/build-android.ps1 -Variant validation first, or pass -SucruneApk."
}
$SucruneApk = (Resolve-Path -LiteralPath $SucruneApk).Path
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot -or -not $JdkHome) { throw 'Set ANDROID_HOME and JAVA_HOME or pass -SdkRoot and -JdkHome.' }
if (-not $Gradle) { $Gradle = Join-Path $projectDir 'gradlew.bat' }
$Gradle = (Resolve-Path -LiteralPath $Gradle).Path

if (-not $SkipGuestPreparation) {
    $prepareArgs = @((Join-Path $PSScriptRoot 'prepare-guest.py'), '--sucrune', $SucruneRoot,
        '--out', (Join-Path $projectDir 'app/build/generated/sugarAssets'))
    if ($Tarballs) { $prepareArgs += @('--tarballs', (Resolve-Path -LiteralPath $Tarballs).Path) }
    if ($Offline) { $prepareArgs += '--offline' }
    $networkArgs = @((Join-Path $PSScriptRoot 'prepare-network-tools.py'), '--sucrune', $SucruneRoot,
        '--out', (Join-Path $projectDir 'app/build/generated/sugarAssets'))
    if ($Tarballs) { $networkArgs += @('--tarballs', (Resolve-Path -LiteralPath $Tarballs).Path) }
    if ($Offline) { $networkArgs += '--offline' }
    & $Python @networkArgs
    if ($LASTEXITCODE -ne 0) { throw "Preparing Linux network tools failed ($LASTEXITCODE)." }
    $mobilePythonArgs = @((Join-Path $PSScriptRoot 'prepare-mobile-python.py'), '--sucrune', $SucruneRoot,
        '--out', (Join-Path $projectDir 'app/build/generated/sugarAssets'))
    if ($PythonProfile) { $mobilePythonArgs += @('--profile', $PythonProfile) }
    if ($Offline) { $mobilePythonArgs += '--offline' }
    & $Python @mobilePythonArgs
    if ($LASTEXITCODE -ne 0) { throw "Preparing mobile Python failed ($LASTEXITCODE)." }
    $pnpmArgs = @((Join-Path $PSScriptRoot 'prepare-pnpm.py'), '--out', (Join-Path $projectDir 'app/build/generated/sugarAssets'))
    if ($Tarballs) { $pnpmArgs += @('--tarballs', (Resolve-Path -LiteralPath $Tarballs).Path) }
    if ($Offline) { $pnpmArgs += '--offline' }
    & $Python @pnpmArgs
    if ($LASTEXITCODE -ne 0) { throw "Preparing pnpm failed ($LASTEXITCODE)." }
    & $Python @prepareArgs
    if ($LASTEXITCODE -ne 0) { throw "Preparing the guest assets failed ($LASTEXITCODE)." }
}

# Validate the exact candidate even when reusing prepared guest assets.
& $Python (Join-Path $PSScriptRoot 'check-dsh-upgrade.py') (Join-Path $projectDir 'app/build/generated/sugarAssets/guest/rootfs.zip')
if ($LASTEXITCODE -ne 0) { throw "The packaged DSH compatibility check failed ($LASTEXITCODE)." }

$settings = @{
    JAVA_HOME = (Resolve-Path -LiteralPath $JdkHome).Path
    ANDROID_HOME = (Resolve-Path -LiteralPath $SdkRoot).Path
    ANDROID_SDK_ROOT = (Resolve-Path -LiteralPath $SdkRoot).Path
    ANDROID_USER_HOME = (Join-Path $projectDir '.build/android-user')
    GRADLE_USER_HOME = (Join-Path $projectDir '.build/gradle-user-home')
}
if ($ReadOnlyGradleCache) { $settings.GRADLE_RO_DEP_CACHE = (Resolve-Path -LiteralPath $ReadOnlyGradleCache).Path }
$previous = @{}
foreach ($key in $settings.Keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
Push-Location -LiteralPath $projectDir
try {
    foreach ($key in $settings.Keys) { [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process') }
    $gradleArgs = @('--no-daemon', "-PsucruneDir=$SucruneRoot", "-PsucruneApk=$SucruneApk", "-PsugarZig=$Zig")
    if ($Offline) { $gradleArgs += '--offline' }
    & $Gradle @gradleArgs ':shared:jvmTest' ':app:testDebugUnitTest' ':app:assembleDebug' ':app:lintDebug'
    if ($LASTEXITCODE -ne 0) { throw "Android build failed ($LASTEXITCODE)." }
    Write-Output (Join-Path $projectDir 'app/build/outputs/apk/debug/app-debug.apk')
} finally {
    Pop-Location
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
