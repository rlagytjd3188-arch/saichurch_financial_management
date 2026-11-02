@echo off
setlocal
cd /d "%~dp0"
if exist "ChurchSteward\ChurchSteward.exe" goto launch
echo Downloading the official Church Steward 1.2.0 Windows app with Java included.
echo Node.js and a separate Java installation are not required.
powershell.exe -NoLogo -NoProfile -Command ^
 "$ErrorActionPreference='Stop';" ^
 "$target=Join-Path (Get-Location).Path 'ChurchSteward';" ^
 "if(Test-Path -LiteralPath $target){throw 'ChurchSteward already exists without an executable. Preserve it and check the folder before retrying.'};" ^
 "$tmp=Join-Path $env:TEMP ('church-steward-'+[guid]::NewGuid().ToString());" ^
 "New-Item -ItemType Directory -Path $tmp | Out-Null;" ^
 "try {" ^
 " $zip=Join-Path $tmp 'app.zip';" ^
 " Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/typemild/church-steward/releases/download/v1.2.0/ChurchSteward-1.2.0-windows.zip' -OutFile $zip;" ^
 " $expected='971fb5a9b69ac99b286f8443a15cf71b99f4b86c2845eac8acc11786db148503';" ^
 " if((Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant() -ne $expected){throw 'Download checksum does not match the official release.'};" ^
 " Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $tmp 'unpacked');" ^
 " $app=Join-Path $tmp 'unpacked\ChurchSteward';" ^
 " if(!(Test-Path -LiteralPath (Join-Path $app 'ChurchSteward.exe'))){throw 'Missing executable in the official archive.'};" ^
 " Move-Item -LiteralPath $app -Destination $target;" ^
 "} finally {Remove-Item -LiteralPath $tmp -Recurse -Force}"
if errorlevel 1 goto failed
:launch
start "" "%~dp0ChurchSteward\ChurchSteward.exe"
exit /b 0
:failed
echo Unable to start. The message above explains the failure. Existing data was not replaced.
pause
exit /b 1
