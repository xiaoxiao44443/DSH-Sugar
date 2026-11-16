[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SucruneRoot,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Zig = $env:ZIG
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
# The preparation scripts need bsdtar's .deb/ar support, not Git's GNU tar.
$env:Path = "$env:SystemRoot/System32;$env:Path"
$SucruneRoot = (Resolve-Path -LiteralPath $SucruneRoot).Path
$readelf = Join-Path $SdkRoot 'ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe'
Push-Location -LiteralPath $SucruneRoot
try {
    & ./scripts/prepare-linux-rootfs.ps1 -IncludeNode
    & ./scripts/prepare-linux-bash.ps1 -ReadElf $readelf
    node scripts/prepare-linux-node-pty.cjs
    python scripts/prepare-linux-stty.py --readelf $readelf
    python scripts/prepare-linux-base-tools.py
    python scripts/prepare-git-curl.py
    python scripts/prepare-linux-python.py
    python scripts/prepare-toolbox-fixture.py
    & ./scripts/build-linux-programs.ps1 -Zig $Zig
    & ./scripts/build-go-android.ps1 -SdkRoot $SdkRoot
    # Sugar needs the runtime libraries/assets, not the large probe instrumentation APK.
    $env:GRADLE_USER_HOME = Join-Path $SucruneRoot '.build/gradle-home'
    & ./gradlew.bat --no-daemon --console=plain '-Psucrune.cmakeVersion=3.22.1' :probeapp:assembleValidation
    if ($LASTEXITCODE) { throw 'Sucrune runtime build failed' }
} finally {
    Pop-Location
}
