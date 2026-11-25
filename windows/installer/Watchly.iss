; Watchly for Windows — installer and uninstaller (Inno Setup 6).
; Built by windows/build-installer.ps1, which publishes the exe first and passes:
;   /DAppVersion=2.5.0   the app's <Version> from Watchly.csproj
;   /DSign + /Swatchly=…  only when a code-signing certificate is configured; then the setup
;                         and its uninstaller are signed too (see README "Signing").

#ifndef AppVersion
  #define AppVersion "2.5.0"
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
; The app is a folder of files now, the runtime and libVLC among them: packed as one, they shrink a lot.
Compression=lzma2
SolidCompression=yes
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
; An addon site's Install button opens stremio:// links. Offered only while no other app (Stremio
; itself) has them, so installing Watchly never takes Stremio's links away.
Name: "stremiolinks"; Description: "Open addon Install links (stremio://) in Watchly"; GroupDescription: "Links:"; Check: StremioLinksFree

[Registry]
Root: HKA; Subkey: "Software\Classes\stremio"; ValueType: string; ValueData: "URL:Stremio addon link"; Flags: uninsdeletekey; Tasks: stremiolinks
Root: HKA; Subkey: "Software\Classes\stremio"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""; Tasks: stremiolinks
Root: HKA; Subkey: "Software\Classes\stremio\DefaultIcon"; ValueType: string; ValueData: """{app}\Watchly.exe"",0"; Tasks: stremiolinks
Root: HKA; Subkey: "Software\Classes\stremio\shell\open\command"; ValueType: string; ValueData: """{app}\Watchly.exe"" ""%1"""; Tasks: stremiolinks

; Watch party invites: the relay's join page hands its link on as watchly://party/…, which is ours alone.
Root: HKA; Subkey: "Software\Classes\watchly"; ValueType: string; ValueData: "URL:Watchly link"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\watchly"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\watchly\DefaultIcon"; ValueType: string; ValueData: """{app}\Watchly.exe"",0"
Root: HKA; Subkey: "Software\Classes\watchly\shell\open\command"; ValueType: string; ValueData: """{app}\Watchly.exe"" ""%1"""

[Dirs]
; libVLC writes its plugin cache (plugins.dat) here on the first play: without it, it opens all 300+
; plugin DLLs every launch. Installed for everyone, under Program Files, that needs leave to write.
Name: "{app}\libvlc\win-x64\plugins"; Permissions: users-modify

[Files]
; The published folder whole: Watchly.exe, the .NET runtime beside it, and libVLC.
Source: "..\dist\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Watchly"; Filename: "{app}\Watchly.exe"
Name: "{autodesktop}\Watchly"; Filename: "{app}\Watchly.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Watchly.exe"; Description: "{cm:LaunchProgram,Watchly}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; What the old single-file exe unpacked to run (libVLC and all, ~250 MB a build) — always safe to remove.
Type: filesandordirs; Name: "{%TEMP}\.net\Watchly"
; libVLC's plugin cache: written by the app, not installed, so not removed with the rest by itself.
Type: files; Name: "{app}\libvlc\win-x64\plugins\plugins.dat"

[Code]
// stremio:// is free, or already Watchly's (an update): anyone else's command is left alone.
function StremioLinksFree(): Boolean;
var
  Command: String;
begin
  Result := True;
  if RegQueryStringValue(HKEY_CLASSES_ROOT, 'stremio\shell\open\command', '', Command) then
    Result := Pos('watchly.exe', Lowercase(Command)) > 0;
end;

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
