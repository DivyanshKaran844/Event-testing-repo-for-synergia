@echo off
REM One command to build (if needed) and run the app on Windows.
REM
REM Usage:
REM   run.bat live          rem print new events to the console as they happen
REM   run.bat report        rem write report.html for the last 30 days
REM   run.bat report 7      rem or a custom window, e.g. last 7 days

REM Always run from this script's own directory - that's where
REM config.properties/target live, and where report.html/live.html get written.
cd /d "%~dp0"

if not exist config.properties (
    echo No config.properties found.
    echo Run:  copy config.properties.example config.properties
    echo then fill in your GitHub/Jira details before running this again.
    exit /b 1
)

if not exist target\event-tracker.jar (
    echo Building...
    call mvn -q package -DskipTests
    if errorlevel 1 exit /b 1
)

java -jar target\event-tracker.jar %*
