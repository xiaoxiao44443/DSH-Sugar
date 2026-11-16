[CmdletBinding()]
param([string]$ToolRoot = (Join-Path $env:RUNNER_TEMP 'sugar-tools'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$zigVersion = '0.17.1'
$zigHash = 'b03f477a1943673e02aca0f570e8030dffd536ce1f45cbc4d3326ec31159562e'
$null = New-Item -ItemType Directory -Force -Path $ToolRoot
$archive = Join-Path $ToolRoot 'zig.zip'
Invoke-WebRequest "https://ziglang.org/download/$zigVersion/zig-x86_64-windows-$zigVersion.zip" -OutFile $archive
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $zigHash) {
    throw 'Zig download checksum mismatch'
}
Expand-Archive -LiteralPath $archive -DestinationPath $ToolRoot -Force
$zig = Join-Path $ToolRoot "zig-x86_64-windows-$zigVersion/zig.exe"
if ((& $zig version).Trim() -ne $zigVersion) { throw 'Unexpected Zig compiler' }
"ZIG=$zig" >> $env:GITHUB_ENV
$sdkmanager = Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat'
1..100 | ForEach-Object { 'y' } | & $sdkmanager --licenses
& $sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0' 'ndk;26.3.11579264' 'cmake;3.22.1'
if ($LASTEXITCODE) { throw 'Android SDK preparation failed' }
