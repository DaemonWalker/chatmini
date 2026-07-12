@echo off
setlocal enabledelayedexpansion

REM ChatMini one-click build and install to local emulator
REM Default installs the arm64 debug APK. The emulator must support ARM translation.

set PROJECT_DIR=E:\workspace\chatmini
set JAVA_HOME=E:\ProgramData\jdk-21
set ANDROID_HOME=E:\ProgramData\android_sdk
set ANDROID_SDK_ROOT=E:\ProgramData\android_sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%PATH%

cd /d %PROJECT_DIR%

echo [1/4] Building Debug APK...
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo Build failed. Please check the Gradle output above.
    pause
    exit /b 1
)

echo [2/4] Connecting to local emulator...
adb connect 127.0.0.1:5555
adb connect 127.0.0.1:16384

echo [3/4] Waiting for device...
adb wait-for-device

echo [4/4] Installing APK...
set APK_PATH=app\build\outputs\apk\arm64\debug\app-arm64-debug.apk

set INSTALLED=0
for %%p in (127.0.0.1:5555 127.0.0.1:16384) do (
    adb -s %%p shell echo ok >nul 2>&1
    if !errorlevel! equ 0 (
        echo Installing to %%p...
        adb -s %%p install -r %APK_PATH%
        if !errorlevel! equ 0 (
            set INSTALLED=1
            echo Install successful: %%p
            goto :done
        ) else (
            echo Install to %%p failed, trying next...
        )
    )
)

:done
if %INSTALLED% equ 0 (
    echo Install failed. Please run "adb devices" and verify the emulator is connected.
) else (
    echo Done.
)

pause
endlocal
