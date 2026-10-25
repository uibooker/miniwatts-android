#!/system/bin/sh
MODDIR=${0%/*}
# Keep the recovery journal if restoring the hardware fails.
/system/bin/sh "$MODDIR/control.sh" off
