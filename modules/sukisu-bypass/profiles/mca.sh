#!/system/bin/sh

MCA_DIR=/sys/devices/platform/soc/soc:smart_charge
MCA_NODE=$MCA_DIR/smart_chg
MCA_USB=/sys/class/power_supply/usb/online
MCA_WIRELESS=/sys/class/power_supply/wireless/online
MCA_BINARY=/vendor_dlkm/lib/modules/mca_smart_charge.ko
MCA_HASH=9c85ed0c761e597df3a549ed25b9a9cfbbc9414fb7b38d2b6a6043bc787510af
MCA_PROFILE_ID=xiaomi-dada-os3.0.303.0-fcc-v1

mca_supported() {
    MCA_WHY=unknown_device
    command -v getprop >/dev/null 2>&1 || { MCA_WHY=getprop_unavailable; return 1; }
    [ "$(getprop ro.product.model 2>/dev/null)" = 24129PN74C ] && [ "$(getprop ro.product.device 2>/dev/null)" = dada ] || return 1
    MCA_WHY=unsupported_firmware
    [ "$(getprop ro.build.version.incremental 2>/dev/null)" = OS3.0.303.0.WOCCNXM ] || return 1
    MCA_WHY=unsupported_kernel
    [ "$(uname -r 2>/dev/null)" = 6.6.77-android15-8-gf9a1d4bd8353-abogki440974771-4k ] || return 1
    MCA_WHY=driver_binary_unavailable
    [ -r "$SYSROOT$MCA_BINARY" ] || return 1
    MCA_WHY=driver_binary_mismatch
    MCA_ACTUAL_HASH=$(sha256sum "$SYSROOT$MCA_BINARY" 2>/dev/null) || return 1
    MCA_ACTUAL_HASH=${MCA_ACTUAL_HASH%% *}
    [ "$MCA_ACTUAL_HASH" = "$MCA_HASH" ] || return 1
    # This verifies a current binding and a vendor file, not the loaded ELF's hash.
    MCA_WHY=driver_not_bound
    [ -L "$SYSROOT$MCA_DIR/driver" ] && [ -d "$SYSROOT$MCA_DIR/driver" ] &&
        [ -e "$SYSROOT$MCA_DIR/driver/soc:smart_charge" ] || return 1
    MCA_ROOT=$(readlink -f "${SYSROOT:-/}" 2>/dev/null) || return 1
    [ "$(readlink -f "$SYSROOT$MCA_DIR/driver" 2>/dev/null)" = "${MCA_ROOT%/}/sys/bus/platform/drivers/smart_charge" ] &&
        [ "$(readlink -f "$SYSROOT$MCA_DIR/driver/soc:smart_charge" 2>/dev/null)" = "${MCA_ROOT%/}$MCA_DIR" ] || return 1
    MCA_WHY=dt_support_missing_or_disabled
    MCA_SUPPORT=$(od -An -tx1 "$SYSROOT$MCA_DIR/of_node/smart_bypass_support" 2>/dev/null | tr -d ' \n')
    [ "$MCA_SUPPORT" = 00000001 ] || return 1
    MCA_WHY=request_node_unavailable
    [ -r "$SYSROOT$MCA_NODE" ] || return 1
    mca_read "$MCA_NODE" >/dev/null || { MCA_WHY=request_state_invalid; return 1; }
    MCA_WHY=matched_vendor_policy
    return 0
}
mca_path() { [ "$1" = "$MCA_NODE" ]; }
mca_read() {
    mca_path "$1" || return 1
    MCA_RAW=$(value "$1") || return 1
    number "$MCA_RAW" || return 1
    # Reject oversized and noncanonical values before shell arithmetic.
    case "$MCA_RAW" in 0) ;; 0*) return 1 ;; esac
    [ "${#MCA_RAW}" -le 5 ] && [ "$MCA_RAW" -le 65535 ] || return 1
    printf '%s\n' "$(((MCA_RAW & 1024) / 1024))"
}
mca_write() {
    mca_path "$1" && boolean "$2" || return 1
    mca_supported || { fail "MCA compatibility check failed: $MCA_WHY"; return 1; }
    [ -w "$SYSROOT$1" ] || return 1
    # smart_chg show is an aggregate. Store accepts a single selector command.
    write_node "$1" "$((1024 + $2))" && [ "$(mca_read "$1")" = "$2" ]
}
mca_guard() {
    mca_supported || { fail "MCA compatibility check failed: $MCA_WHY"; return 1; }
    [ "$(value "$MCA_WIRELESS")" = 0 ] || { fail 'Wireless state missing, invalid or online'; return 1; }
    number "$TEMP" && [ "$TEMP" -ge 0 ] && [ "$TEMP" -lt 800 ] || { fail 'Battery temperature is not meaningful'; return 1; }
    BOARD_TEMP=$(value "$MCA_DIR/board_temp")
    number "$BOARD_TEMP" && [ "$BOARD_TEMP" -ge 0 ] && [ "$BOARD_TEMP" -lt 800 ] && [ "$BOARD_TEMP" -lt "$MAX" ] || {
        fail 'Board temperature missing, invalid or above limit'; return 1;
    }
}
