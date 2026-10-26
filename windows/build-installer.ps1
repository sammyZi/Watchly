# Builds windows/installer/Output/Watchly-Setup-<version>-x64.exe: publishes the self-contained
# exe to windows/dist, signs it when a certificate is configured, then packs it with Inno Setup.
#
#   powershell -ExecutionPolicy Bypass -File windows/build-installer.ps1
#
# Signing (optional — without it Windows shows "Unknown publisher" / SmartScreen):
#   $env:WATCHLY_SIGN_PFX = 'C:\path\cert.pfx'; $env:WATCHLY_SIGN_PASSWORD = '...'   a certificate file, or
#   $env:WATCHLY_SIGN_THUMBPRINT = 'AB12...'                                           one in your certificate store
#                                                                                      (an EV token, for one)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$project = Join-Path $here 'Watchly\Watchly.csproj'
$dist = Join-Path $here 'dist'

$version = ([xml](Get-Content $project)).Project.PropertyGroup.Version | Where-Object { $_ } | Select-Object -First 1
if (-not $version) { $version = '2.0.0' }

$iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw 'Inno Setup 6 is not installed — get it from https://jrsoftware.org/isdl.php' }

if (Get-Process Watchly -ErrorAction SilentlyContinue) { throw 'Close Watchly first — a running Watchly.exe cannot be overwritten.' }
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
dotnet publish $project -c Release -r win-x64 --self-contained -o $dist -nologo
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed' }

# The newest signtool in the Windows SDK, and how to call it with the configured certificate.
$sign = $null
if ($env:WATCHLY_SIGN_PFX -or $env:WATCHLY_SIGN_THUMBPRINT) {
    $signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $signtool) { throw 'signtool.exe not found — install the Windows SDK' }
    $cert = if ($env:WATCHLY_SIGN_PFX) { "/f `"$env:WATCHLY_SIGN_PFX`" /p `"$env:WATCHLY_SIGN_PASSWORD`"" } else { "/sha1 $env:WATCHLY_SIGN_THUMBPRINT" }
    $sign = "`"$($signtool.FullName)`" sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /d Watchly $cert"
    Write-Host 'Signing Watchly.exe'
    cmd /c "$sign `"$dist\Watchly.exe`""
    if ($LASTEXITCODE -ne 0) { throw 'Signing Watchly.exe failed' }
}

$args = @("/DAppVersion=$version")
if ($sign) { $args += '/DSign'; $args += "/Swatchly=$sign `$f" }
& $iscc @args (Join-Path $here 'installer\Watchly.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup failed' }
Write-Host "Built $(Join-Path $here "installer\Output\Watchly-Setup-$version-x64.exe")$(if (-not $sign) { ' (unsigned)' })"
