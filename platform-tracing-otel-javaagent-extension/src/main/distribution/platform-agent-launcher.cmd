@echo off
setlocal
set "DISTRIBUTION_DIR=%~dp0"

java -javaagent:"%DISTRIBUTION_DIR%opentelemetry-javaagent.jar" %*
exit /b %errorlevel%
