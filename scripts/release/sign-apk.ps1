[CmdletBinding()]
param(
    [string]$Repo = (Join-Path $PSScriptRoot '../..'),
    [string]$SdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$Out,
    [string]$Python = 'python'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Add-GitHubValue([string]$Variable, [string]$Name, [string]$Value) {
    $destination = [Environment]::GetEnvironmentVariable($Variable)
    if (-not $destination) { return }
    if ($Value.Contains("`r") -or $Value.Contains("`n")) { throw 'GitHub metadata must be one line.' }
    [IO.File]::AppendAllText($destination, "$Name=$Value`n", [Text.UTF8Encoding]::new($false))
}

function Assert-ApkManifest([string]$Apk, [object]$Version, [string]$Aapt) {
    $badging = @(& $Aapt dump badging $Apk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect the release APK manifest.' }
    $packages = @($badging | Where-Object { "$_" -match '^package:' })
    if ($packages.Count -ne 1) { throw 'APK package metadata is missing or ambiguous.' }
    $line = [string]$packages[0]
    $packageName = [regex]::Match($line, "\bname='([^']+)'").Groups[1].Value
    $versionName = [regex]::Match($line, "\bversionName='([^']+)'").Groups[1].Value
    $versionCode = [regex]::Match($line, "\bversionCode='([^']+)'").Groups[1].Value
    if ($packageName -cne 'app.dsh.sugar') { throw 'Unexpected APK package name.' }
    if ($versionName -cne $Version.version -or $versionCode -cne [string]$Version.version_code) {
        throw 'APK version does not match app/build.gradle.'
    }
    if (@($badging | Where-Object { "$_" -match '^application-debuggable(?:\s|$)' }).Count -ne 0) {
        throw 'Refusing to publish a debuggable APK.'
    }
    $native = @($badging | Where-Object { "$_" -match '^native-code:' })
    if ($native.Count -ne 1 -or [string]$native[0] -cnotmatch "^native-code:\s*'arm64-v8a'\s*$") {
        throw 'The release APK must contain only the arm64-v8a ABI.'
    }
}

$repository = (Resolve-Path -LiteralPath $Repo).Path
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot) { throw 'Pass -SdkRoot or set ANDROID_SDK_ROOT.' }
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
if (-not $Out) { $Out = Join-Path $repository 'dist' }
$outputDirectory = [IO.Path]::GetFullPath($Out)
$buildTools = Join-Path $sdk 'build-tools/36.0.0'
$aapt = Join-Path $buildTools 'aapt2.exe'
$zipalign = Join-Path $buildTools 'zipalign.exe'
$signer = Join-Path $buildTools 'apksigner.bat'
foreach ($tool in @($aapt, $zipalign, $signer)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing Android build tool: $tool" }
}
$versionJson = & $Python (Join-Path $PSScriptRoot 'inspect-version.py') --repo $repository
if ($LASTEXITCODE -ne 0) { throw 'Release version validation failed.' }
$version = ($versionJson -join "`n") | ConvertFrom-Json
$unsigned = Join-Path $repository 'app/build/outputs/apk/release/app-release-unsigned.apk'
if (-not (Test-Path -LiteralPath $unsigned -PathType Leaf)) { throw 'Run :app:assembleRelease before signing.' }
Assert-ApkManifest $unsigned $version $aapt
$alignment = @(& $zipalign -c -P 16 4 $unsigned 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Unsigned APK failed the 16 KiB zipalign check.' }

foreach ($name in @('ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
        throw "Missing signing secret: $name"
    }
}
$temporaryRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$temporaryRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
$temporaryDirectory = Join-Path $temporaryRoot ('sugar-sign-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temporaryDirectory
$temporaryKey = Join-Path $temporaryDirectory 'release.keystore'
$temporaryApk = Join-Path $temporaryDirectory 'signed.apk'
$expectedCertificate = '2ac1c58b6b0dfe1e1d6a0965ae7bc729c3f3bb7011b6c78fcf20cef5d1bf2dbd'

try {
    try { $keyBytes = [Convert]::FromBase64String($env:ANDROID_KEYSTORE_BASE64) }
    catch { throw 'ANDROID_KEYSTORE_BASE64 is not valid base64.' }
    if ($keyBytes.Length -eq 0) { throw 'The decoded signing keystore is empty.' }
    [IO.File]::WriteAllBytes($temporaryKey, $keyBytes)
    [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    $keyBytes = $null

    # Passwords remain in environment variables; neither command arguments nor
    # logs contain their values. Capture signing diagnostics rather than echoing
    # external-tool output with potentially sensitive keystore information.
    $signingOutput = @(& $signer sign --ks $temporaryKey --ks-key-alias $env:ANDROID_KEY_ALIAS `
        --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD `
        --v4-signing-enabled false --out $temporaryApk $unsigned 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed; check the signing secrets.' }
    $certificateOutput = @(& $signer verify --verbose --print-certs $temporaryApk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK verification failed.' }
    $certificates = @($certificateOutput | ForEach-Object {
        if ([string]$_ -match '^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$') {
            $Matches[1].ToLowerInvariant()
        }
    })
    if ($certificates.Count -ne 1 -or $certificates[0] -cne $expectedCertificate) {
        throw 'Signing certificate does not match the existing DSH Sugar release.'
    }
    $alignment = @(& $zipalign -c -P 16 4 $temporaryApk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK failed the 16 KiB zipalign check.' }
    Assert-ApkManifest $temporaryApk $version $aapt
    $null = New-Item -ItemType Directory -Path $outputDirectory -Force
    $outputApk = Join-Path $outputDirectory $version.apk_name
    Copy-Item -LiteralPath $temporaryApk -Destination $outputApk -Force
    $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputApk).Hash.ToLowerInvariant()
    Add-GitHubValue 'GITHUB_OUTPUT' 'apk_path' $outputApk
    Add-GitHubValue 'GITHUB_OUTPUT' 'apk_sha256' $sha256
    Add-GitHubValue 'GITHUB_ENV' 'SUGAR_APK_PATH' $outputApk
    Add-GitHubValue 'GITHUB_ENV' 'SUGAR_APK_SHA256' $sha256
    [pscustomobject]@{version = $version.version; version_code = $version.version_code; apk = $outputApk;
        sha256 = $sha256; certificate_sha256 = $expectedCertificate} | ConvertTo-Json -Compress
} finally {
    if ($null -ne (Get-Variable -Name keyBytes -ErrorAction SilentlyContinue) -and $null -ne $keyBytes) {
        [Array]::Clear($keyBytes, 0, $keyBytes.Length)
    }
    # Remove only the two known temporary files, then their empty directory.
    # No recursive deletion or unrelated runner files are involved.
    foreach ($temporaryFile in @($temporaryKey, $temporaryApk)) {
        if (Test-Path -LiteralPath $temporaryFile -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryFile -Force
        }
    }
    Remove-Item -LiteralPath $temporaryDirectory -Force
}
