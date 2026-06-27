@echo off
chcp 65001 >nul
echo ========================================
echo  Mineradio Android APK Builder
echo ========================================

set PROJECT_DIR=%~dp0
set NODE_PROJECT=%PROJECT_DIR%app\src\main\assets\nodejs-project

echo [1/3] Installing Node.js backend dependencies...
cd /d "%NODE_PROJECT%"
call npm install
if %ERRORLEVEL% NEQ 0 (
    echo Warning: npm install failed, some music features may not work.
)

echo [2/3] Setting JAVA_HOME...
set JAVA_HOME=D:\APP\Java

echo [3/3] Building APK...
cd /d "%PROJECT_DIR%"
call gradlew assembleDebug

echo.
echo APK should be at: app\build\outputs\apk\debug\
pause
