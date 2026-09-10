#!/usr/bin/env bash
# Build Aquarius and install it on a connected device.
#
# Builds first, always. Installing whatever happens to be in build/outputs is
# how you end up debugging yesterday's APK.
#
#   ./deploy.sh                      # uses the only connected device
#   DEVICE=192.168.1.50:5555 ./deploy.sh
#   DEVICE=ABCD1234 EXPECT_SERIAL=ABCD1234 ./deploy.sh
#
# DEVICE          adb target (serial, or host:port for adb over TCP). If unset,
#                 the single connected device is used, and having more than one
#                 connected is an error rather than a coin flip.
# EXPECT_SERIAL   if set, ro.serialno must match before anything is installed.
#                 Worth setting when DEVICE is an IP: addresses get reassigned,
#                 and installing a debug build onto the wrong phone is not the
#                 kind of mistake you notice quickly.
# GRADLE          gradle entry point, default ./gradlew.
set -euo pipefail

cd "$(dirname "$0")"

DEVICE="${DEVICE:-}"
EXPECT_SERIAL="${EXPECT_SERIAL:-}"
GRADLE="${GRADLE:-./gradlew}"

APK=app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
PKG=dev.volo.aquarius

echo "==> build"
"$GRADLE" assembleDebug

if [ ! -f "$APK" ]; then
    echo "no APK at $APK" >&2
    echo "the build only produces arm64-v8a; there is no other ABI to fall back to" >&2
    exit 1
fi

echo "==> device"
if [ -n "$DEVICE" ]; then
    # Harmless for a plain serial; connects if it is a host:port.
    case "$DEVICE" in *:*) adb connect "$DEVICE" >/dev/null 2>&1 || true ;; esac
    ADB=(adb -s "$DEVICE")
else
    count=$(adb devices | grep -cw device || true)
    if [ "$count" -ne 1 ]; then
        echo "found $count devices; set DEVICE=<serial|host:port>" >&2
        adb devices >&2
        exit 1
    fi
    ADB=(adb)
fi

"${ADB[@]}" wait-for-device

if [ -n "$EXPECT_SERIAL" ]; then
    got=$("${ADB[@]}" shell getprop ro.serialno | tr -d '\r')
    if [ "$got" != "$EXPECT_SERIAL" ]; then
        echo "refusing to install: device serial is '$got', expected '$EXPECT_SERIAL'" >&2
        exit 1
    fi
fi

echo "==> install ($(du -h "$APK" | cut -f1))"
# Expect about a minute. Most of the APK is libxul.so, and the device has to
# verify and optimise the whole thing.
"${ADB[@]}" install -r "$APK"

echo "==> launch"
"${ADB[@]}" shell am start -W -n "$PKG/.MainActivity"
