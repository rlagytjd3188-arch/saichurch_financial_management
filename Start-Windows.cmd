@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Install Node.js 24 LTS or newer from https://nodejs.org first.
  pause
  exit /b 1
)
node -e "if(Number(process.versions.node.split('.')[0])<24)process.exit(1)"
if errorlevel 1 (
  echo Node.js 24 or newer is required.
  pause
  exit /b 1
)
if not exist node_modules (
  echo Installing dependencies. Internet is required for this step.
  call npm ci --no-audit --no-fund
  if errorlevel 1 goto failed
)
if not exist .next\BUILD_ID (
  echo Building the application. Please wait.
  call npm run build
  if errorlevel 1 goto failed
)
echo Starting Church Finance. Keep this window open while using the app.
echo Open http://127.0.0.1:3000 in your browser.
start "" "http://127.0.0.1:3000"
call npm start
if errorlevel 1 goto failed
exit /b 0
:failed
echo Startup failed. Please copy the error message above for troubleshooting.
pause
exit /b 1
