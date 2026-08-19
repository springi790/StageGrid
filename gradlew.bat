@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "APP_HOME=%~dp0"
set "WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"

rem Resolve a usable Java installation. Prefer Android Studio's bundled JDK so
rem a malformed JAVA_HOME inherited from Windows cannot break batch parsing.
set "JAVA_EXE="

if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" (
  set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
  set "JAVA_EXE=%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe"
)

if not defined JAVA_EXE (
  if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
    set "JAVA_EXE=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe"
  )
)

rem Only inspect an existing JAVA_HOME after the safe defaults above. Delayed
rem expansion prevents characters such as > or & inside a broken value from
rem being interpreted as batch syntax.
if not defined JAVA_EXE if defined JAVA_HOME (
  if exist "!JAVA_HOME!\bin\java.exe" (
    set "JAVA_EXE=!JAVA_HOME!\bin\java.exe"
  )
)

rem Fall back to java.exe already available on PATH. Clear an invalid
rem JAVA_HOME so Gradle itself does not reject it.
if not defined JAVA_EXE (
  for /f "delims=" %%J in ('where java.exe 2^>nul') do (
    if not defined JAVA_EXE set "JAVA_EXE=%%J"
  )
  if defined JAVA_EXE set "JAVA_HOME="
)

if not defined JAVA_EXE (
  echo.
  echo ERROR: No usable Java installation was found.
  echo StageGrid requires JDK 17 or newer.
  echo Install Android Studio, or set JAVA_HOME to a valid JDK folder.
  echo Example: C:\Program Files\Android\Android Studio\jbr
  echo.
  exit /b 1
)

if defined JAVA_HOME set "PATH=!JAVA_HOME!\bin;!PATH!"

if exist "%WRAPPER_JAR%" (
  "!JAVA_EXE!" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
  exit /b !ERRORLEVEL!
)

set "GRADLE_VERSION=9.5.1"
set "EXPECTED_SHA256=bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f"
if "%GRADLE_USER_HOME%"=="" set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "CACHE_DIR=%GRADLE_USER_HOME%\stagegrid-bootstrap"
set "ZIP=%CACHE_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_HOME=%CACHE_DIR%\gradle-%GRADLE_VERSION%"
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
  for /f "tokens=*" %%H in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower()"') do set "ACTUAL_SHA256=%%H"
  if /I not "!ACTUAL_SHA256!"=="%EXPECTED_SHA256%" (
    echo StageGrid: Gradle checksum mismatch; refusing to execute unverified download.
    del /q "%ZIP%" 2>nul
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force -Path '%ZIP%' -DestinationPath '%CACHE_DIR%'"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b !ERRORLEVEL!
