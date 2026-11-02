@echo off
setlocal
cd /d "%~dp0"
if exist "app\saichurch-1.5.1.txt" if exist "ChurchSteward.exe" goto here
if exist "ChurchSteward\app\saichurch-1.5.1.txt" if exist "ChurchSteward\ChurchSteward.exe" goto packaged
if exist "Build-Windows.cmd" (
  call Build-Windows.cmd
  exit /b
)
echo Download the customized Sai Church 1.5.1 Windows ZIP and extract all files first.
echo Open ChurchSteward.exe inside the extracted folder. Java is included in that ZIP.
pause
exit /b 1
:here
start "" "%~dp0ChurchSteward.exe"
exit /b 0
:packaged
start "" "%~dp0ChurchSteward\ChurchSteward.exe"
exit /b 0
