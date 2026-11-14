[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $AppVersion,
    [Parameter(Mandatory)][string] $PublishDir,
    [string] $OutputDir = (Join-Path $PSScriptRoot '../build/installer'),
    [string] $Compiler
)

$ErrorActionPreference = 'Stop'

$versionPattern = '^(?<number>[0-9]{1,5}(?:\.[0-9]{1,5}){1,3})(?:[-+][0-9A-Za-z][0-9A-Za-z.+-]*)?$'
if ($AppVersion -cnotmatch $versionPattern) {
    throw "Invalid installer version: $AppVersion"
}
$components = $Matches.number.Split('.')
foreach ($component in $components) {
    if ([int]$component -gt 65535) { throw 'Windows file version components must be at most 65535.' }
}
$numericVersion = ($components + @('0', '0', '0', '0') | Select-Object -First 4) -join '.'

$publishPath = (Resolve-Path -LiteralPath $PublishDir).Path.TrimEnd('\', '/')
$outputPath = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDir).TrimEnd('\', '/')
if ($outputPath.StartsWith($publishPath + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $outputPath.Equals($publishPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The installer output must be outside the published application directory.'
}
foreach ($relativePath in 'RolithaxLauncher.exe', 'RolithaxLauncher.pri', 'App.xbf', 'MainWindow.xbf',
    'coreclr.dll', 'Microsoft.UI.Xaml.dll', 'rolithax_core.dll', 'legal/WindowsAppSDK-LICENSE.txt',
    'legal/WindowsAppSDK-NOTICE.txt') {
    $file = Get-Item -LiteralPath (Join-Path $publishPath $relativePath)
    if ($file.PSIsContainer -or $file.Length -eq 0) { throw "Missing installer payload file: $relativePath" }
}

$toolsPath = Join-Path $PSScriptRoot '../build/installer-tools'
New-Item -ItemType Directory -Path $outputPath, $toolsPath -Force | Out-Null
$toolsPath = (Resolve-Path -LiteralPath $toolsPath).Path

function Get-VerifiedFile([string] $Url, [string] $Path, [string] $Sha256) {
    if ((Test-Path -LiteralPath $Path -PathType Leaf) -and
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256) {
        return
    }
    $temporaryFile = "$Path.$([Guid]::NewGuid()).tmp"
    try {
        curl.exe --fail --silent --show-error --location --retry 2 --connect-timeout 15 --max-time 90 $Url --output $temporaryFile
        if ($LASTEXITCODE -ne 0) { throw "Could not download $Url" }
        if ((Get-FileHash -LiteralPath $temporaryFile -Algorithm SHA256).Hash -ne $Sha256) {
            throw "Checksum mismatch for $Url"
        }
        Move-Item -LiteralPath $temporaryFile -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryFile) { Remove-Item -LiteralPath $temporaryFile }
    }
}

$innoVersion = '6.7.4'
$portableCompiler = Join-Path $toolsPath "Inno Setup $innoVersion"
if (!$Compiler) {
    $iscc = Get-Command ISCC.exe -CommandType Application -ErrorAction SilentlyContinue
    $programRoots = [Environment]::GetFolderPath('ProgramFilesX86'), [Environment]::GetFolderPath('ProgramFiles'),
        (Join-Path $env:LOCALAPPDATA 'Programs')
    $candidates = @(if ($iscc) { $iscc.Source }
        foreach ($directory in $programRoots) {
            Join-Path $directory 'Inno Setup 6/ISCC.exe'
        }
        Join-Path $portableCompiler 'ISCC.exe'
    )
    $Compiler = $candidates | Where-Object {
        if (Test-Path -LiteralPath $_ -PathType Leaf) {
            $version = [Diagnostics.FileVersionInfo]::GetVersionInfo($_)
            $version.FileMajorPart -eq 6 -and $version.FileMinorPart -ge 6
        }
    } | Select-Object -First 1
}

if (!$Compiler) {
    $innoSetup = Join-Path $toolsPath "innosetup-$innoVersion.exe"
    Get-VerifiedFile "https://github.com/jrsoftware/issrc/releases/download/is-6_7_4/innosetup-$innoVersion.exe" $innoSetup (
        'a549929319f9120716677c685535ca4671e4b937b5fdc87e2585f32d8eb30e8c')
    $install = Start-Process -FilePath $innoSetup -ArgumentList @(
        '/PORTABLE=1', '/CURRENTUSER', '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', ('/DIR="{0}"' -f $portableCompiler)
    ) -WindowStyle Hidden -Wait -PassThru
    if ($install.ExitCode -ne 0) { throw "Inno Setup compiler installation failed: $($install.ExitCode)" }
    $Compiler = Join-Path $portableCompiler 'ISCC.exe'
}

$compilerPath = (Resolve-Path -LiteralPath $Compiler).Path
$chineseMessages = Join-Path $toolsPath 'ChineseSimplified.isl'
Get-VerifiedFile 'https://raw.githubusercontent.com/jrsoftware/issrc/is-6_7_4/Files/Languages/Unofficial/ChineseSimplified.isl' $chineseMessages (
    '7d544b9bb1d142cfa11f2e5d3cc8abe2e55f8e066c5124e3772675aa236e1278')

$compilerArgs = @('/Qp', "/DAppVersion=$AppVersion", "/DVersionNumber=$numericVersion", "/DPublishDir=$publishPath",
    "/DOutputDir=$outputPath", "/DChineseMessages=$chineseMessages", (Join-Path $PSScriptRoot 'RolithaxLauncher.iss'))
& $compilerPath @compilerArgs
if ($LASTEXITCODE -ne 0) { throw "Inno Setup compiler failed: $LASTEXITCODE" }
$setupExe = Get-Item -LiteralPath (Join-Path $outputPath 'RolithaxLauncher-Windows-x64-Setup.exe')
if ($setupExe.Length -eq 0) { throw 'The Windows installer is empty.' }
$setupExe.FullName
