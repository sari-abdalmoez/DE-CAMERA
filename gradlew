#!/bin/sh
set -e
if [ ! -x "$HOME/.gradle/wrapper/dists/gradle-8.9-bin" ] && ! command -v gradle >/dev/null 2>&1; then
  echo 'Gradle wrapper bootstrap: repository expects Gradle 8.9. Use Android Studio/Gradle wrapper generation or CI setup.'
fi
exec gradle "$@"
