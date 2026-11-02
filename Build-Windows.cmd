@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 goto nojava
javac -version 2>&1 | findstr /R "^javac 25\." >nul
if errorlevel 1 goto nojava
call gradlew.bat --no-daemon --max-workers=2 test bootJar
if errorlevel 1 goto failed
if not exist "ChurchSteward-Source" mkdir "ChurchSteward-Source"
copy /Y "build\libs\church-steward-1.2.0.jar" "ChurchSteward-Source\church-steward.jar" >nul
if errorlevel 1 goto failed
java -jar "ChurchSteward-Source\church-steward.jar"
if errorlevel 1 goto failed
exit /b 0
:nojava
echo Building the source requires JDK 25, including javac on PATH.
echo For the official app with Java included, run Start-Windows.cmd instead.
pause
exit /b 1
:failed
echo Build or startup failed. Check the error above. Existing data was not deleted.
pause
exit /b 1
