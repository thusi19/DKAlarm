@ECHO OFF
SETLOCAL
SET ROOT=%~dp0
SET VER=8.11.1
SET BASE=%ROOT%.gradle-bootstrap
SET DIST=%BASE%\gradle-%VER%
SET ZIP=%BASE%\gradle-%VER%-bin.zip
IF NOT EXIST "%DIST%\bin\gradle.bat" (
  IF NOT EXIST "%BASE%" MKDIR "%BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%VER%-bin.zip' -OutFile '%ZIP%'; Expand-Archive -Force '%ZIP%' '%BASE%'"
  IF ERRORLEVEL 1 EXIT /B 1
)
CALL "%DIST%\bin\gradle.bat" %*
ENDLOCAL
