#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  work_adb="$ANDROID_SDK_ROOT/platform-tools/adb"
elif [ -n "${ANDROID_HOME:-}" ]; then
  work_adb="$ANDROID_HOME/platform-tools/adb"
elif command -v adb >/dev/null 2>&1; then
  work_adb=$(command -v adb)
else
  echo 'Set ANDROID_SDK_ROOT or ANDROID_HOME, or add adb to PATH.' >&2
  exit 1
fi
"$work_adb" reverse tcp:8797 tcp:8797
cd "$project_dir"
umask 077
exec python3 integration/server.py --port 8797
