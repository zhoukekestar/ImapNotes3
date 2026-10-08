#!/usr/bin/env bash
set -euo pipefail
apk=${1:?APK required}
tag=${2:?Version tag required}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
tools_dir="$sdk/build-tools/37.0.0"
"$tools_dir/apksigner" verify --verbose --print-certs "$apk"
badging=$("$tools_dir/aapt" dump badging "$apk")
version=${tag#v}
[[ "$badging" == *"name='io.github.zhoukekestar.imapnotes3'"* ]]
[[ "$badging" == *"versionName='$version'"* ]]
[[ "$badging" != *"application-debuggable"* ]]
printf 'Verified package, version %s, and non-debuggable release APK.\n' "$version"
