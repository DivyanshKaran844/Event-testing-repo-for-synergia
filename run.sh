#!/usr/bin/env bash
# One command to build (if needed) and run the app.
#
# Usage:
#   ./run.sh live          # print new events to the console as they happen
#   ./run.sh report        # write report.html for the last 30 days
#   ./run.sh report 7      # or a custom window, e.g. last 7 days
set -e

# Always run from this script's own directory, regardless of where it's called from -
# that's where config.properties/target live, and where report.html/live.html get written.
cd "$(dirname "$0")"

if [ ! -f config.properties ]; then
    echo "No config.properties found."
    echo "Run:  cp config.properties.example config.properties"
    echo "then fill in your GitHub/Jira details before running this again."
    exit 1
fi

if [ ! -f target/event-tracker.jar ] || [ "$(find src -newer target/event-tracker.jar -name '*.java' 2>/dev/null)" != "" ]; then
    echo "Building..."
    mvn -q package -DskipTests
fi

java -jar target/event-tracker.jar "$@"
