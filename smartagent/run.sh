#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ -z "${DEEPSEEK_STUDY_API_KEY:-}" ] && [ -z "${OPENROUTER_STUDY_API_KEY:-}" ]; then
  echo "Note: Add DEEPSEEK_STUDY_API_KEY or OPENROUTER_STUDY_API_KEY to local.properties" >&2
  echo ""
fi

GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.appname=SmartReminder"
if [ $# -gt 0 ]; then
  exec ./gradlew :smartagent:run --console=plain --args="$*"
else
  exec ./gradlew :smartagent:run --console=plain
fi
