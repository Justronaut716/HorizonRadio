#!/usr/bin/env bash
# Quick practical comparison: yt-dlp versus HorizonRadio on the same audio track(s).
# No retries or cooldown waits. Usage: scripts/compare-youtube-audio.sh [video-id-or-URL ...]
set -u
cd "$(dirname "$0")/.."

inputs=("$@")
if [ "${#inputs[@]}" -eq 0 ]; then
  inputs=("M7lc1UVf-VE")
fi

if ! command -v yt-dlp >/dev/null 2>&1; then
  echo "yt-dlp is required for the comparison."
  exit 2
fi

tmp=$(mktemp -d "${TMPDIR:-/tmp}/horizonradio-audio-compare.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

yt_status=0
for i in "${!inputs[@]}"; do
  input="${inputs[$i]}"
  echo "=== yt-dlp: $input ==="
  timeout 60 yt-dlp --retries 0 --extractor-retries 0 --socket-timeout 15 \
    --no-playlist -f bestaudio -o "$tmp/yt-$i.%(ext)s" "$input"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    yt_status=1
    echo "yt-dlp result: FAIL (exit $rc)"
  else
    echo "yt-dlp result: PASS"
  fi
  echo
 done

echo "=== HorizonRadio ==="
scripts/test-youtube-audio.sh "${inputs[@]}"
our_status=$?
if [ "$our_status" -eq 0 ]; then
  echo "HorizonRadio result: PASS"
else
  echo "HorizonRadio result: FAIL (exit $our_status)"
fi

[ "$yt_status" -eq 0 ] && [ "$our_status" -eq 0 ]
