@echo off
cd /d "%~dp0backend"
call npm ci
if errorlevel 1 exit /b 1
echo CarSync server starts on port 8080. Keep this window open.
echo Configure both cars with this computer's private Wi-Fi address and port 8080.
call npm start
