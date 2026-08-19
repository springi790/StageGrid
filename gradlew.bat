@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if exist "%WRAPPER_JAR%" (
  java -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
  exit /b %ERRORLEVEL%
)

set GRADLE_VERSION=9.5.1
set EXPECTED_SHA256=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE_DIR=%GRADLE_USER_HOME%\stagegrid-bootstrap
set ZIP=%CACHE_DIR%\gradle-%GRADLE_VERSION%-bin.zip
set GRADLE_HOME=%CACHE_DIR%\gradle-%GRADLE_VERSION%
if not exist "%CACHE_DIR%" mkdir "%CACHE_DIR%"

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%ZIP%" (
    echo StageGrid: downloading Gradle %GRADLE_VERSION% from services.gradle.org ...
    where curl.exe >nul 2>nul
    if not errorlevel 1 (
      curl.exe -fL --retry 3 --retry-delay 2 -o "%ZIP%" "https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
    ) else (
      powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%'"
    )
    if errorlevel 1 (
      del /q "%ZIP%" 2>nul
      exit /b 1
    )
  )
  for /f "tokens=*" %%H in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower()"') do set ACTUAL_SHA256=%%H
  if /I not "%ACTUAL_SHA256%"=="%EXPECTED_SHA256%" (
    echo StageGrid: Gradle checksum mismatch; refusing to execute unverified download.
    del /q "%ZIP%" 2>nul
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%CACHE_DIR%'"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
