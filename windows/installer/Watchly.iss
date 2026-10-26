; Watchly for Windows — installer and uninstaller (Inno Setup 6).
; Built by windows/build-installer.ps1, which publishes the exe first and passes:
;   /DAppVersion=2.0.0   the app's <Version> from Watchly.csproj
;   /DSign + /Swatchly=…  only when a code-signing certificate is configured; then the setup
;                         and its uninstaller are signed too (see README "Signing").

#ifndef AppVersion
  #define AppVersion "2.0.0"
#endif

[Setup]
; Never change AppId: it is how an update finds the copy already installed.
AppId={{8C1F4E2A-6B7D-4C59-9A3E-5D2F7B1C9E40}
AppName=Watchly
AppVersion={#AppVersion}
AppVerName=Watchly {#AppVersion}
AppPublisher=Watchly
AppPublisherURL=https://github.com/sammyZi/Watchly
AppSupportURL=https://github.com/sammyZi/Watchly/issues
AppUpdatesURL=https://github.com/sammyZi/Watchly/releases
; For this Windows account by default — no administrator prompt — or for everyone, if chosen.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
DefaultDirName={autopf}\Watchly
DefaultGroupName=Watchly
DisableProgramGroupPage=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
SetupIconFile=..\Watchly\Assets\app.ico
UninstallDisplayIcon={app}\Watchly.exe
UninstallDisplayName=Watchly
OutputDir=Output
OutputBaseFilename=Watchly-Setup-{#AppVersion}-x64
; The exe is already compressed inside; packing it again only costs time.
Compression=lzma2/fast
SolidCompression=no
WizardStyle=modern
; A running Watchly is closed (and offered a restart) rather than the install failing on a locked file.
CloseApplications=yes
RestartApplications=no
#ifdef Sign
SignTool=watchly
SignedUninstaller=yes
#endif

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\dist\Watchly.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Watchly"; Filename: "{app}\Watchly.exe"
Name: "{autodesktop}\Watchly"; Filename: "{app}\Watchly.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Watchly.exe"; Description: "{cm:LaunchProgram,Watchly}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; What the exe unpacks to run (libVLC and all, ~250 MB a build) — always safe to remove.
Type: filesandordirs; Name: "{%TEMP}\.net\Watchly"

[Code]
// Profiles, history, My List, settings and the SubDL key live in %APPDATA%\Watchly; the stream
// cache in %LOCALAPPDATA%\Watchly. Asked, not assumed: someone reinstalling wants to keep them.
// Downloaded films in the downloads folder are never touched.
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Library, Cache: String;
begin
  if CurUninstallStep <> usPostUninstall then Exit;
  Library := ExpandConstant('{userappdata}\Watchly');
  Cache := ExpandConstant('{localappdata}\Watchly');
  if not DirExists(Library) and not DirExists(Cache) then Exit;
  if UninstallSilent then Exit;
  if MsgBox('Also delete your Watchly profiles, watch history, My List and settings?' + #13#10 + #13#10 +
            'Choose No to keep them for a later reinstall. Downloaded videos are not deleted either way.',
            mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
  begin
    DelTree(Library, True, True, True);
    DelTree(Cache, True, True, True);
  end;
end;
