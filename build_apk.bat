@echo off
rem One-click APK build script.
rem Override these env vars to use custom locations:
rem   JAVA_HOME       JDK 17 home (default D:\AndroidDev\jdk-home\jdk-17.0.20+8)
rem   ANDROID_HOME    Android SDK root (default D:\AndroidDev\sdk)
rem   GRADLE_USER_HOME Gradle cache (default D:\AndroidDev\gradle-home)
if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\AndroidDev\jdk-home\jdk-17.0.20+8"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=D:\AndroidDev\sdk"
if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=D:\AndroidDev\gradle-home"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0PaddleOcrApp"
call gradlew.bat %*
if %errorlevel%==0 (
  echo.
  echo BUILD OK: %~dp0PaddleOcrApp\app\build\outputs\apk\debug\app-debug.apk
) else (
  echo.
  echo BUILD FAILED
)
