#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
project_root="$PWD"
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ "$(uname -s)" == Darwin ]] && command -v java >/dev/null; then
        JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 |
            awk '/^[[:space:]]*java.home = / {sub(/^[[:space:]]*java.home = /, ""); print; exit}')
        export JAVA_HOME
    else
        local_jdk=("$project_root"/.tooling/jdk-*)
        if [[ ! -x "${local_jdk[0]}/bin/java" ]]; then
            echo 'Set JAVA_HOME to JDK 17 or run scripts/bootstrap-arm64.sh.' >&2
            exit 1
        fi
        export JAVA_HOME="${local_jdk[0]}"
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME="$project_root/.tooling/gradle-home"
export ANDROID_HOME="${ANDROID_HOME:-$project_root/.tooling/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$project_root/.tooling/android-user-home}"
if [[ ! -f .tooling/debug.keystore ]]; then
    mkdir -p .tooling
    keytool -genkeypair -keystore .tooling/debug.keystore -storepass android -keypass android \
        -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
        -keyalg RSA -keysize 2048 -validity 10000
fi
args=(--no-daemon)
if [[ "$(uname -m)" == 'aarch64' ]]; then
    aapt2_path="$project_root/.tooling/arm-tools/build-tools/aapt2"
    [[ -x "$aapt2_path" ]] || { echo 'Run scripts/bootstrap-arm64.sh for native ARM64 aapt2.' >&2; exit 1; }
    args+=("-Pandroid.aapt2FromMavenOverride=$aapt2_path")
fi
./gradlew "${args[@]}" :core:test :app:assembleDebug :app:lintDebug "$@"
