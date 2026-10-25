#!/system/bin/sh
umask 077
MODDIR=${0%/*}
# Entry points always use real Android paths; the library's simulator is test-only.
SYSROOT=
STATE=/data/adb/miniwatts-bypass
. "$MODDIR/lib.sh"
main "$@"
