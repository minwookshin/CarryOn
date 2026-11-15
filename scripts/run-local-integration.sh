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
work_port=${CARRYON_TEST_PORT:-8798}
"$work_adb" reverse "tcp:$work_port" "tcp:$work_port"
cd "$project_dir"
umask 077
exec python3 integration/server.py --port "$work_port" --db "${CARRYON_TEST_DATABASE:-$project_dir/integration/local-state-demo.sqlite3}"
