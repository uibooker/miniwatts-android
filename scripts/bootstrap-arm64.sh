#!/usr/bin/env bash
# Tools stay inside this repository; SDK license acceptance is interactive.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(uname -m)" == aarch64 ]] || { echo 'This bootstrap is for Linux ARM64.' >&2; exit 1; }
available_kb=$(df -Pk . | awk 'NR==2 {print $4}')
(( available_kb >= 2500000 )) || { echo 'Need at least 2.5 GB free.' >&2; exit 1; }
mkdir -p .tooling
fetch() {
    local url="$1" dest="$2" checksum="$3"
    if [[ ! -f "$dest" ]]; then curl --fail --location --retry 3 "$url" -o "$dest"; fi
    printf '%s  %s\n' "$checksum" "$dest" | sha256sum -c -
}
fetch 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.20.1_1.tar.gz' .tooling/jdk.tar.gz 457b57af8f9c93ec39080bb8c764f559dc8c89a6da1a39d718a400b7890d3e41
[[ -d .tooling/jdk-17.0.20.1+1 ]] || tar -xf .tooling/jdk.tar.gz -C .tooling
export JAVA_HOME="$PWD/.tooling/jdk-17.0.20.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
fetch 'https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip' .tooling/sdk-tools.zip 2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258
if [[ ! -d .tooling/android-sdk/cmdline-tools/latest ]]; then
    mkdir -p .tooling/android-sdk/cmdline-tools
    unzip -q .tooling/sdk-tools.zip -d .tooling/android-sdk/cmdline-tools
    mv .tooling/android-sdk/cmdline-tools/cmdline-tools .tooling/android-sdk/cmdline-tools/latest
fi
fetch 'https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip' .tooling/arm-tools.zip db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2
[[ -d .tooling/arm-tools ]] || unzip -q .tooling/arm-tools.zip -d .tooling/arm-tools
sdk_manager="$PWD/.tooling/android-sdk/cmdline-tools/latest/bin/sdkmanager"
"$sdk_manager" --sdk_root="$PWD/.tooling/android-sdk" --licenses
"$sdk_manager" --sdk_root="$PWD/.tooling/android-sdk" 'platforms;android-35' 'build-tools;35.0.0'
.tooling/arm-tools/build-tools/aapt2 version
printf 'Ready. Run scripts/build-local.sh\n'
