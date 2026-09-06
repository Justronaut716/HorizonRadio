#!/usr/bin/env bash
# One-shot manual test of HorizonRadio's production YouTube audio downloader.
# Usage: scripts/test-youtube-audio.sh [video-id-or-URL ...]
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v java >/dev/null 2>&1; then
  echo "Java 25 is required. Set JAVA_HOME and add its bin directory to PATH."
  exit 2
fi

echo "[youtube-audio] building production jar" >&2
./gradlew -q jar --rerun-tasks
echo "[youtube-audio] production jar built" >&2
jar=$(find build/libs -maxdepth 1 -name 'horizonradio-*.jar' ! -name '*sources*' -printf '%T@ %p\n' | sort -nr | head -1 | cut -d' ' -f2-)
gson=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.google.code.gson" -name 'gson-*.jar' ! -name '*sources*' | sort -V | tail -1)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/horizonradio-audio-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

echo "[youtube-audio] compiling smoke test" >&2
javac -cp "$jar:$gson" -d "$tmp" scripts/YouTubeAudioSmokeTest.java
echo "[youtube-audio] starting smoke test (60 second limit)" >&2
set +e
timeout --foreground --kill-after=5 60 java -cp "$jar:$gson:$tmp" YouTubeAudioSmokeTest "$@"
status=$?
set -e
if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    echo "[youtube-audio] TIMEOUT: smoke test exceeded 60 seconds" >&2
fi
exit "$status"
