[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $Setup,
    [string] $UpgradeSetup
)

$ErrorActionPreference = 'Stop'
$setupPath = (Resolve-Path -LiteralPath $Setup).Path
$upgradePath = if ($UpgradeSetup) { (Resolve-Path -LiteralPath $UpgradeSetup).Path }
$uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{2F61A33B-5598-49F9-A129-3AF04EBDBDD4}_is1'
if (Test-Path -LiteralPath $uninstallKey) {
    throw 'Installer smoke requires a Windows account without an existing Rolithax installation.'
}
$scratchPath = Join-Path ([IO.Path]::GetTempPath()) "Rolithax-setup-test-$([Guid]::NewGuid())"
$installPath = Join-Path $scratchPath 'Rolithax Launcher'
$uninstallerPath = Join-Path $installPath 'unins000.exe'
$silentArgs = '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART'

function Invoke-SetupProcess([string] $Path, [string[]] $Arguments) {
    $process = Start-Process -FilePath $Path -ArgumentList $Arguments -WindowStyle Hidden -PassThru -Wait
    if ($process.ExitCode -ne 0) { throw "$([IO.Path]::GetFileName($Path)) returned $($process.ExitCode)." }
}

try {
    $installArgs = $silentArgs + @('/SP-', '/MERGETASKS=!startmenu,!desktopicon')
    Invoke-SetupProcess $setupPath ($installArgs + ('/DIR="{0}"' -f $installPath))
    if ($upgradePath) {
        Invoke-SetupProcess $upgradePath $installArgs
        $upgradeVersion = (Get-Item -LiteralPath $upgradePath).VersionInfo.ProductVersion.Trim()
        if ((Get-ItemPropertyValue -LiteralPath $uninstallKey -Name DisplayVersion).Trim() -ne $upgradeVersion) {
            throw 'The installer did not update the registered version.'
        }
    }
    if ((Get-ItemPropertyValue -LiteralPath $uninstallKey -Name InstallLocation).TrimEnd('\') -ne $installPath) {
        throw 'Setup did not register the expected per-user installation directory.'
    }
    foreach ($relativePath in 'RolithaxLauncher.exe', 'RolithaxLauncher.pri', 'App.xbf', 'MainWindow.xbf',
        'coreclr.dll', 'Microsoft.UI.Xaml.dll', 'rolithax_core.dll', 'Assets/Rolithax.ico',
        'legal/WindowsAppSDK-LICENSE.txt', 'unins000.exe') {
        if ((Get-Item -LiteralPath (Join-Path $installPath $relativePath)).Length -eq 0) {
            throw "Installed file is empty: $relativePath"
        }
    }
} finally {
    if (Test-Path -LiteralPath $uninstallerPath -PathType Leaf) {
        Invoke-SetupProcess $uninstallerPath $silentArgs
    }
}
if ((Test-Path -LiteralPath $uninstallKey) -or (Test-Path -LiteralPath (Join-Path $installPath 'RolithaxLauncher.exe'))) {
    throw 'Uninstall did not remove the application and its registration.'
}
if ((Test-Path -LiteralPath $scratchPath -PathType Container) -and
    !(Get-ChildItem -LiteralPath $scratchPath -Force | Select-Object -First 1)) {
    Remove-Item -LiteralPath $scratchPath
}
'Windows installer smoke passed.'
