#define AppName "Rolithax Launcher"
#define AppExe "RolithaxLauncher.exe"
#define RepositoryUrl "https://github.com/WLudy1012/RolithaxLauncher"

[Setup]
AppId={{2F61A33B-5598-49F9-A129-3AF04EBDBDD4}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Rolithax
AppPublisherURL={#RepositoryUrl}
AppSupportURL={#RepositoryUrl}/issues
AppUpdatesURL={#RepositoryUrl}/releases
VersionInfoVersion={#VersionNumber}
VersionInfoProductName={#AppName}
VersionInfoProductTextVersion={#AppVersion}
MinVersion=10.0.17763
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
PrivilegesRequired=lowest
DefaultDirName={userpf}\{#AppName}
UsePreviousAppDir=yes
DisableDirPage=auto
DisableProgramGroupPage=yes
DisableWelcomePage=no
WizardStyle=modern dynamic
SetupIconFile=..\Assets\Rolithax.ico
UninstallDisplayIcon={app}\{#AppExe}
CloseApplications=yes
CloseApplicationsFilter=RolithaxLauncher.exe,*.dll,*.pri,*.xbf
RestartApplications=no
Compression=lzma2/max
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename=RolithaxLauncher-Windows-x64-Setup

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "chinesesimplified"; MessagesFile: "{#ChineseMessages}"

[CustomMessages]
english.NewInstall=Set up Rolithax Launcher for this Windows account. The installer includes the application and its runtimes, so setup can run offline.
chinesesimplified.NewInstall=为当前 Windows 账户安装 Rolithax Launcher。安装包已包含程序及其运行时，可以离线安装。
english.ExistingInstall=Update Rolithax Launcher in the existing installation folder. Your Daemon connections and appearance settings are kept.
chinesesimplified.ExistingInstall=在原安装目录中更新 Rolithax Launcher。已保存的 Daemon 连接和外观设置会保留。
english.ExistingFolderRequired=Install the update in the existing folder. To move the program, uninstall it first.
chinesesimplified.ExistingFolderRequired=请在原目录中更新。如需更换目录，请先卸载程序。
english.StartMenuShortcut=Create a Start menu shortcut
chinesesimplified.StartMenuShortcut=创建开始菜单快捷方式

[Tasks]
Name: "startmenu"; Description: "{cm:StartMenuShortcut}"
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked

[Files]
Source: "{#PublishDir}\*"; DestDir: "{app}"; Excludes: "*.pdb"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Tasks: startmenu
Name: "{userdesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExe}"; WorkingDir: "{app}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[Code]
procedure InitializeWizard;
begin
  if WizardForm.PrevAppDir = '' then
    WizardForm.WelcomeLabel2.Caption := CustomMessage('NewInstall')
  else
    WizardForm.WelcomeLabel2.Caption := CustomMessage('ExistingInstall');
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  Result := '';
  if (WizardForm.PrevAppDir <> '') and
     (CompareText(RemoveBackslashUnlessRoot(WizardDirValue),
                  RemoveBackslashUnlessRoot(WizardForm.PrevAppDir)) <> 0) then
    Result := CustomMessage('ExistingFolderRequired');
end;
