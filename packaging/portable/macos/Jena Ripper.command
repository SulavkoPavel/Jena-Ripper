#!/bin/bash
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
DATA_HOME="$HOME/.jena-ripper"
LOG_HOME="$HOME/Library/Logs/JenaRipper"

mkdir -p "$DATA_HOME" "$LOG_HOME"

exec "$APP_HOME/runtime/bin/java" \
  -Dfile.encoding=UTF-8 \
  -Dspring.profiles.active=desktop \
  "-Djena-ripper.settings.path=$DATA_HOME/profiles.json" \
  "-Dlogging.file.name=$LOG_HOME/jena-ripper.log" \
  -jar "$APP_HOME/jena-ripper.jar" "$@"
