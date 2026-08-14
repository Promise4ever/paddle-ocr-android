@echo off
rem Generate a release signing keystore (keystore.jks) and keystore.properties.
rem WARNING: keep keystore.jks and the passwords safe; losing them means you
rem can never update the released app.
if "%JAVA_HOME%"=="" set "JAVA_HOME=D:\AndroidDev\jdk-home\jdk-17.0.20+8"
if not exist "%JAVA_HOME%\bin\keytool.exe" (
  echo keytool not found under %JAVA_HOME%. Set JAVA_HOME first.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"

if exist keystore.jks (
  echo keystore.jks already exists, skip.
) else (
  keytool -genkeypair -v -keystore keystore.jks -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10950 ^
    -alias paddleocr -storepass paddleocr123 -keypass paddleocr123 -dname "CN=PaddleOCR, OU=Dev, O=Dev, L=City, ST=State, C=CN"
)

echo storeFile=keystore.jks> keystore.properties
echo storePassword=paddleocr123>> keystore.properties
echo keyAlias=paddleocr>> keystore.properties
echo keyPassword=paddleocr123>> keystore.properties
echo.
echo Done: keystore.jks + keystore.properties created.
echo Change the default passwords in keystore.properties before publishing.
