@echo off
echo Building VoiceMesh APK...

REM Set Gradle user home to avoid permission issues
set GRADLE_USER_HOME=C:\Users\raahi\gradle-home

REM Set Java options
set GRADLE_OPTS=-Xmx2048m -Dfile.encoding=UTF-8

REM Ensure gradle home directory exists
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

REM Clean and build
echo Cleaning previous build...
gradlew.bat clean

echo Building debug APK...
gradlew.bat assembleDebug

echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    echo ✅ SUCCESS! APK created at: app\build\outputs\apk\debug\app-debug.apk
    echo File size: 
    dir "app\build\outputs\apk\debug\app-debug.apk"
) else (
    echo ❌ Build failed. Check error messages above.
)

pause 