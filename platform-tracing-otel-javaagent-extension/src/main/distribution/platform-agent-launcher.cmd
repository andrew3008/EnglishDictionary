@echo off
setlocal
set "DISTRIBUTION_DIR=%~dp0"

java -jar "%DISTRIBUTION_DIR%platform-agent-verifier.jar" verify "%DISTRIBUTION_DIR%" -- %*
if errorlevel 1 exit /b %errorlevel%

java -javaagent:"%DISTRIBUTION_DIR%opentelemetry-javaagent.jar" %*
exit /b %errorlevel%
