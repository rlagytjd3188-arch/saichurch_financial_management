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
copy /Y "build\libs\church-steward-1.3.0.jar" "ChurchSteward-Source\church-steward.jar" >nul
if errorlevel 1 goto failed
java -jar "ChurchSteward-Source\church-steward.jar"
if errorlevel 1 goto failed
exit /b 0
:nojava
echo Building this modified source requires JDK 25, including javac on PATH.
echo For a ready-to-run app, use the customized Sai Church Windows ZIP.
echo GitHub: Actions - Windows app - latest successful run - Artifacts.
echo https://github.com/rlagytjd3188-arch/saichurch_financial_management/actions
pause
exit /b 1
:failed
echo Build or startup failed. Check the error above. Existing data was not deleted.
pause
exit /b 1
