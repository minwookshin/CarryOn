#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${CARRYON_KEYSTORE:?Set CARRYON_KEYSTORE to a keystore outside this repository}"
: "${CARRYON_STORE_PASSWORD_FILE:?Set CARRYON_STORE_PASSWORD_FILE to a private password file}"
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
: "${sdk_dir:?Set ANDROID_SDK_ROOT or ANDROID_HOME}"
build_tools="$sdk_dir/build-tools/${CARRYON_BUILD_TOOLS_VERSION:-35.0.0}"
output=${CARRYON_APK_OUTPUT:-"$project_dir/apk/CarryOn-2.10-release.apk"}
cd "$project_dir"
./gradlew --no-daemon assembleDemoRelease
unsigned="$project_dir/app/build/outputs/apk/demoRelease/app-demoRelease-unsigned.apk"
aligned="$project_dir/app/build/outputs/apk/demoRelease/app-demoRelease-aligned.apk"
"$build_tools/zipalign" -f -p 4 "$unsigned" "$aligned"
mkdir -p "$(dirname -- "$output")"
set -- sign --ks "$CARRYON_KEYSTORE" \
  --ks-key-alias "${CARRYON_KEY_ALIAS:-carryon-demo}" \
  --ks-pass "file:$CARRYON_STORE_PASSWORD_FILE" \
  --out "$output" "$aligned"
if [ -n "${CARRYON_KEY_PASSWORD_FILE:-}" ]; then
  set -- sign --ks "$CARRYON_KEYSTORE" \
    --ks-key-alias "${CARRYON_KEY_ALIAS:-carryon-demo}" \
    --ks-pass "file:$CARRYON_STORE_PASSWORD_FILE" \
    --key-pass "file:$CARRYON_KEY_PASSWORD_FILE" --out "$output" "$aligned"
fi
"$build_tools/apksigner" "$@"
"$build_tools/apksigner" verify --verbose "$output"
printf '\nSigned demo release: %s\n' "$output"
