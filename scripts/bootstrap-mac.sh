#!/usr/bin/env bash
# Official SDK packages and downloads stay inside this repository.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(uname -s)" == Darwin ]] || { echo 'This bootstrap is for macOS.' >&2; exit 1; }
available_kb=$(df -Pk . | awk 'NR==2 {print $4}')
(( available_kb >= 2500000 )) || { echo 'Need at least 2.5 GB free.' >&2; exit 1; }
if [[ -z "${JAVA_HOME:-}" ]]; then
    JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 |
        awk '/^[[:space:]]*java.home = / {sub(/^[[:space:]]*java.home = /, ""); print; exit}')
    export JAVA_HOME
fi
[[ -x "$JAVA_HOME/bin/java" ]] || { echo 'Set JAVA_HOME to a real JDK 17 path.' >&2; exit 1; }
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_USER_HOME="$PWD/.tooling/android-user-home"
archive=.tooling/downloads/sdk-tools-mac.zip
mkdir -p .tooling/downloads
if [[ ! -f "$archive" ]]; then
    curl --fail --location --retry 3 \
        https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip \
        -o "$archive"
fi
printf '%s  %s\n' 7bc5c72ba0275c80a8f19684fb92793b83a6b5c94d4d179fc5988930282d7e64 "$archive" |
    shasum -a 256 -c -
sdk_root="$PWD/.tooling/android-sdk"
if [[ ! -d "$sdk_root/cmdline-tools/latest" ]]; then
    mkdir -p "$sdk_root/cmdline-tools"
    unzip -q "$archive" -d "$sdk_root/cmdline-tools"
    mv "$sdk_root/cmdline-tools/cmdline-tools" "$sdk_root/cmdline-tools/latest"
fi
# A fresh installation prompts the operator to accept the SDK license.
"$sdk_root/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk_root" \
    'platforms;android-35' 'build-tools;35.0.0'
printf 'Ready. Run scripts/build-local.sh -PisolatedDebug=true\n'
