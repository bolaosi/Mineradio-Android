@echo off
setlocal
cd /d "%~dp0"
rem Set JAVA_HOME to JDK 17 or 21 and ANDROID_HOME to an installed Android SDK.
rem Official API clients are built in; no backend deployment is needed. Node is excluded.
call gradlew.bat --no-daemon lintDebug testDebugUnitTest assembleDebug
if errorlevel 1 exit /b %errorlevel%
echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
