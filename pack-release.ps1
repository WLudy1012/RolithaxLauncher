[CmdletBinding()]
param(
    [string] $VersionName,
    [string] $JdkHome = $env:JAVA_HOME,
    [string] $AndroidSdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
if (!$JdkHome -or !(Test-Path -LiteralPath (Join-Path $JdkHome 'bin/java.exe') -PathType Leaf)) {
    throw '请设置 JAVA_HOME，或用 -JdkHome 指向 JDK。'
}
if (!$AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (!$AndroidSdk) { $AndroidSdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Join-Path $AndroidSdk 'build-tools'
$signer = Get-ChildItem -LiteralPath $buildTools -Directory |
    Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
    Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
    Select-Object -First 1
if (!$signer) { throw 'Android SDK 中缺少 apksigner。' }

$oldJava = $env:JAVA_HOME
$env:JAVA_HOME = $JdkHome
Push-Location -LiteralPath $root
try {
    $gradleArgs = @(':app:assembleRelease', '--no-build-cache', '--no-configuration-cache')
    if ($VersionName) { $gradleArgs += "-PversionName=$VersionName" }
    & '.\gradlew.bat' @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw '构建签名 APK 失败。' }

    $apk = Join-Path $root 'app/build/outputs/apk/release/app-release.apk'
    $entries = & tar.exe -tf $apk
    if ($LASTEXITCODE -ne 0 -or 'AndroidManifest.xml' -cnotin $entries -or
        ($entries | Where-Object { $_ -like 'assets/jre/*' })) {
        throw 'APK 内容无效或包含内置 JRE。'
    }
    $verification = & $signer verify --verbose $apk
    if ($LASTEXITCODE -ne 0 -or
        $verification -cnotcontains 'Verified using v2 scheme (APK Signature Scheme v2): true' -or
        $verification -cnotcontains 'Verified using v3 scheme (APK Signature Scheme v3): true') {
        throw '发布 APK 未通过 v2 和 v3 签名验证。'
    }

    $dist = Join-Path $root 'build/dist'
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $out = Join-Path $dist 'RolithaxLauncher.apk'
    Copy-Item -LiteralPath $apk -Destination $out -Force
    $hash = (Get-FileHash -LiteralPath $out -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$out.sha256" -Value "$hash *RolithaxLauncher.apk" -Encoding ascii
    $out
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJava
}
