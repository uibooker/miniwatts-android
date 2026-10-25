#!/bin/sh
# Root and identity injection exist only in this test harness, never entrypoints.
umask 077
MODDIR=$1
SYSROOT=$2
STATE=$3
MODEL=$4
shift 4
getprop() { cat "$SYSROOT/mock-props/$1" 2>/dev/null; }
uname() {
    if [ "$1" = -r ]; then cat "$SYSROOT/mock-kernel"; else command uname "$@"; fi
}
sha256sum() {
    case "$1" in
        "$SYSROOT/vendor_dlkm/lib/modules/mca_smart_charge.ko")
            printf '%s  %s\n' "$(cat "$SYSROOT/mock-hash")" "$1" ;;
        *) command sha256sum "$@" ;;
    esac
}
. "$MODDIR/lib.sh"
if [ "${TEST_REAL_FLOCK:-0}" != 1 ]; then
    kernel_flock() { "$TEST_PYTHON" "$TEST_FLOCK" "$@" 9>&9; }
fi
value() {
    if [ "$1" = /sys/devices/platform/soc/soc:smart_charge/smart_chg ] && [ -f "$SYSROOT/fail-next-read" ]; then
        rm "$SYSROOT/fail-next-read"
        return 1
    fi
    cat "$SYSROOT$1" 2>/dev/null
}
write_node() {
    case "$1" in
        /sys/devices/platform/soc/soc:smart_charge/smart_chg)
            "$TEST_PYTHON" "$MODEL" "$SYSROOT" "$1" "$2" ;;
        *) printf '%s\n' "$2" > "$SYSROOT$1" ;;
    esac
}
if [ "${TEST_PREVIEW:-0}" = 1 ]; then main "$@"; exit $?; fi
init || exit 1
if [ "${NO_WATCHDOG:-0}" != 1 ]; then heartbeat; fi
case "$1" in
    test_hold_lock)
        lock || exit 1
        printf '%s\n' "$$" > "$STATE/lock-held"
        while :; do :; done ;;
    test_tick)
        lock || exit 1
        tick
        TEST_RESULT=$?
        unlock
        exit "$TEST_RESULT" ;;
esac
main "$@"
