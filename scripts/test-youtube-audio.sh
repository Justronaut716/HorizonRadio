#!/usr/bin/env bash
# One-shot manual test of HorizonRadio's production YouTube audio downloader.
# Usage: scripts/test-youtube-audio.sh [video-id-or-URL ...]
set -euo pipefail
cd "$(dirname "$0")/.."

./gradlew -q jar
jar=$(find build/libs -maxdepth 1 -name 'horizonradio-*.jar' ! -name '*sources*' ! -name '*-dev*' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)
gson=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson" -name 'gson-*.jar' ! -name '*sources*' | sort | tail -1)

exec java -cp "$jar:$gson" scripts/YouTubeAudioSmokeTest.java "$@"
