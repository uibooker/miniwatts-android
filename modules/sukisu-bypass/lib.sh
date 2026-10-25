#!/system/bin/sh

fail() { printf '%s\n' "$*" >&2; return 1; }
value() { cat "$SYSROOT$1" 2>/dev/null; }
field() { sed -n "s/^$1=//p" "$2" 2>/dev/null; }
number() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; }
boolean() { [ "$1" = 0 ] || [ "$1" = 1 ]; }
load_mca() {
    [ "${MCA_LOADED:-0}" = 1 ] && return 0
    [ -r "$MODDIR/profiles/mca.sh" ] || { fail 'MCA profile implementation unavailable'; return 1; }
    . "$MODDIR/profiles/mca.sh"
    MCA_LOADED=1
}
backend_path() {
    case "$1" in
        native-boolean) native_path "$2" ;;
        xiaomi-mca-policy) load_mca && mca_path "$2" ;;
        *) return 1 ;;
    esac
}
backend_read() {
    case "$1" in
        native-boolean) native_path "$2" && value "$2" ;;
        xiaomi-mca-policy) load_mca && mca_read "$2" ;;
        *) return 1 ;;
    esac
}
backend_write() {
    backend_path "$1" "$2" && boolean "$3" || return 1
    case "$1" in
        native-boolean) [ -r "$SYSROOT$2" ] && [ -w "$SYSROOT$2" ] && write_node "$2" "$3" && [ "$(value "$2")" = "$3" ] ;;
        xiaomi-mca-policy) mca_write "$2" "$3" ;;
    esac
}
path_ok() {
    case "$1" in *..*|*[!a-zA-Z0-9_/-]*|'') return 1 ;; esac
    case "$1" in /sys/class/power_supply/*/*|/sys/devices/platform/*/*) return 0 ;; esac
    return 1
}
native_path() {
    path_ok "$1" || return 1
    # Names alone do not prove semantics; the operator must verify the driver.
    case "${1##*/}" in bypass_charging|bypass_charging_enable|bypass_mode|charge_bypass) return 0 ;; esac
    return 1
}
online_path() {
    path_ok "$1" || return 1
    case "$1" in /sys/class/power_supply/*/online) return 0 ;; esac
    return 1
}
clock_now() { cut -d. -f1 "$SYSROOT/proc/uptime" 2>/dev/null; }
log() {
    printf '%s %s\n' "$(clock_now)" "$*" >> "$STATE/events.log"
    if [ "$(wc -c < "$STATE/events.log")" -gt 65536 ]; then
        tail -n 100 "$STATE/events.log" > "$STATE/events.tmp"
        mv "$STATE/events.tmp" "$STATE/events.log"
    fi
}
init() {
    mkdir -p "$STATE" || return 1
    chmod 700 "$STATE" || return 1
    BOOT=$(value /proc/sys/kernel/random/boot_id)
    case "$BOOT" in ''|*[!a-zA-Z0-9-]*) fail 'Cannot read boot ID'; return 1 ;; esac
}
kernel_flock() { /data/adb/ksu/bin/busybox flock "$@" 9>&9; }
lock() {
    # Keep the inode persistent: unlinking an active flock file permits two locks.
    exec 9>>"$STATE/controller.lock" || return 1
    LOCK_TRIES=0
    while ! kernel_flock -n 9; do
        LOCK_TRIES=$((LOCK_TRIES + 1))
        if [ "$LOCK_TRIES" -ge 4 ]; then
            exec 9>&-
            fail 'Controller lock unavailable or busy; no hardware writes performed'
            return 1
        fi
        sleep 1 9>&-
    done
    LOCK_HELD=1
}
unlock() {
    [ "${LOCK_HELD:-0}" = 1 ] || return 0
    kernel_flock -u 9
    UNLOCK_RESULT=$?
    exec 9>&-
    LOCK_HELD=0
    return "$UNLOCK_RESULT"
}
load_config() {
    [ -r "$STATE/config" ] || { fail 'Profile missing or unreadable'; return 1; }
    BACKEND=$(field backend "$STATE/config")
    BACKEND=${BACKEND:-native-boolean}
    NODE=$(field node "$STATE/config")
    ON=$(field on "$STATE/config")
    OFF=$(field off "$STATE/config")
    ONLINE=$(field online "$STATE/config")
    MIN=$(field min "$STATE/config")
    MAX=$(field max "$STATE/config")
    backend_path "$BACKEND" "$NODE" && boolean "$ON" && boolean "$OFF" && [ "$ON" != "$OFF" ] && online_path "$ONLINE" && number "$MIN" && number "$MAX" || {
        fail 'No valid profile. Run diagnose, then configure a driver-verified native bypass node.'; return 1;
    }
    [ "$MIN" -ge 10 ] && [ "$MIN" -le 95 ] && [ "$MAX" -ge 300 ] && [ "$MAX" -le 500 ] || return 1
    if [ "$BACKEND" = xiaomi-mca-policy ]; then
        [ "$ON" = 1 ] && [ "$OFF" = 0 ] && [ "$ONLINE" = "$MCA_USB" ] || return 1
    fi
}
write_config() {
    printf 'backend=%s\nnode=%s\non=%s\noff=%s\nonline=%s\nmin=%s\nmax=%s\n' "$BACKEND" "$NODE" "$ON" "$OFF" "$ONLINE" "$MIN" "$MAX" > "$STATE/config.tmp" &&
        mv "$STATE/config.tmp" "$STATE/config"
}
guard() {
    [ "$(value "$ONLINE")" = 1 ] || { fail 'External input is not online'; return 1; }
    CAP=$(value /sys/class/power_supply/battery/capacity)
    TEMP=$(value /sys/class/power_supply/battery/temp)
    number "$CAP" && [ "$CAP" -le 100 ] && [ "$CAP" -ge "$MIN" ] || { fail 'Battery capacity missing, invalid or below limit'; return 1; }
    number "$TEMP" && [ "$TEMP" -lt "$MAX" ] || { fail 'Battery temperature missing, invalid or above limit'; return 1; }
    [ "$BACKEND" != xiaomi-mca-policy ] || mca_guard || return 1
    [ -r "$SYSROOT$NODE" ] && [ -w "$SYSROOT$NODE" ] || { fail 'Native bypass node unavailable or not writable'; return 1; }
    [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || { fail 'Module disabled or pending removal'; return 1; }
}
write_node() {
    # Transport only: each backend verifies its own readback semantics.
    printf '%s\n' "$2" > "$SYSROOT$1"
}
load_journal() {
    [ -r "$STATE/journal" ] || { fail 'Recovery journal unreadable'; return 1; }
    JBACKEND=$(field backend "$STATE/journal")
    JBACKEND=${JBACKEND:-native-boolean}
    JBOOT=$(field boot "$STATE/journal")
    JNODE=$(field node "$STATE/journal")
    JORIG=$(field original "$STATE/journal")
    JON=$(field on "$STATE/journal")
    backend_path "$JBACKEND" "$JNODE" && boolean "$JORIG" && boolean "$JON" && [ "$JORIG" != "$JON" ] && [ -n "$JBOOT" ] || {
        fail 'Invalid recovery journal; manual recovery required'; return 1;
    }
    case "$JBOOT" in *[!a-zA-Z0-9-]*) fail 'Invalid journal boot ID'; return 1 ;; esac
    if [ "$JBACKEND" = xiaomi-mca-policy ]; then
        [ "$JORIG" = 0 ] && [ "$JON" = 1 ] && [ "$(field profile_id "$STATE/journal")" = "$MCA_PROFILE_ID" ] || {
            fail 'Invalid MCA recovery journal or profile identity'; return 1;
        }
    fi
}
journal_matches_config() {
    load_journal && [ "$JBOOT" = "$BOOT" ] && [ "$JBACKEND" = "$BACKEND" ] &&
        [ "$JNODE" = "$NODE" ] && [ "$JON" = "$ON" ] && [ "$JORIG" = "$OFF" ]
}
mark_recovery_pending() {
    printf 'boot=%s\npid=%s\n' "$BOOT" "$$" > "$STATE/recovery_pending.tmp" &&
        mv "$STATE/recovery_pending.tmp" "$STATE/recovery_pending"
}
restore_record() {
    [ -e "$STATE/journal" ] || return 0
    load_journal || return 1
    if [ "$JBOOT" != "$BOOT" ]; then
        log 'Previous-boot journal discarded without writing to the new kernel'
        rm -f "$STATE/journal"
        return $?
    fi
    # Restoration is based exclusively on the journal, even if config is lost.
    if [ "$JBACKEND" = xiaomi-mca-policy ]; then
        mca_supported || { fail "MCA recovery refused: $MCA_WHY; journal retained"; return 1; }
    fi
    CURRENT=$(backend_read "$JBACKEND" "$JNODE") || { fail 'Cannot read recovery node; journal retained'; return 1; }
    if [ "$CURRENT" != "$JORIG" ]; then
        [ "$CURRENT" = "$JON" ] || { fail 'Unexpected node value; recovery journal retained'; return 1; }
        backend_write "$JBACKEND" "$JNODE" "$JORIG" || { fail 'Restore failed; recovery journal retained'; return 1; }
    fi
    log "Restored $JNODE to $JORIG"
    rm -f "$STATE/journal"
}
restore() {
    if restore_record; then
        rm -f "$STATE/recovery_pending" && return 0
    fi
    mark_recovery_pending || fail 'Cannot record pending recovery; recovery journal retained'
    return 1
}
watchdog_alive() {
    DBOOT=$(field boot "$STATE/watchdog")
    DPID=$(field pid "$STATE/watchdog")
    [ "$DBOOT" = "$BOOT" ] && number "$DPID" && kill -0 "$DPID" 2>/dev/null
}
watchdog_ready() {
    [ ! -e "$STATE/recovery_pending" ] || { fail 'Watchdog is retrying recovery; new sessions are blocked'; return 1; }
    watchdog_alive || { fail 'Watchdog not running. Reboot after installing the module.'; return 1; }
    DTIME=$(field time "$STATE/watchdog")
    NOW=$(clock_now)
    number "$DTIME" && number "$NOW" &&
        [ "$NOW" -ge "$DTIME" ] && [ "$((NOW - DTIME))" -le 15 ] || {
        fail 'Watchdog not running. Reboot after installing the module.'; return 1;
    }
}
enable() {
    if ! load_config || ! watchdog_ready || ! guard; then
        restore
        return 1
    fi
    if [ -e "$STATE/journal" ]; then
        journal_matches_config && [ "$(backend_read "$BACKEND" "$NODE")" = "$ON" ] && return 0
        restore || return 1
    fi
    ORIGINAL=$(backend_read "$BACKEND" "$NODE") || { fail 'Cannot read backend request state'; return 1; }
    [ "$ORIGINAL" = "$OFF" ] || { fail 'Node is not in the configured off state; another controller may own it'; return 1; }
    JOURNAL_PROFILE=native-boolean-v1
    [ "$BACKEND" != xiaomi-mca-policy ] || JOURNAL_PROFILE=$MCA_PROFILE_ID
    printf 'backend=%s\nprofile_id=%s\nboot=%s\nnode=%s\noriginal=%s\non=%s\n' "$BACKEND" "$JOURNAL_PROFILE" "$BOOT" "$NODE" "$ORIGINAL" "$ON" > "$STATE/journal.tmp" &&
        mv "$STATE/journal.tmp" "$STATE/journal" || return 1
    if ! backend_write "$BACKEND" "$NODE" "$ON"; then
        log 'Enable write/readback failed; attempting rollback'
        restore
        return 1
    fi
    if [ "$BACKEND" = xiaomi-mca-policy ]; then
        log 'Vendor smart bypass FCC-limit request accepted; power_path=unverified'
        printf '%s\n' 'Vendor FCC-limit policy requested. Hardware power path remains unverified.'
    else
        log "Native bypass requested at $NODE (hardware power path unverified)"
        printf '%s\n' 'Native bypass request accepted. Verify the actual power path on this device.'
    fi
}
configure() {
    [ "$#" = 4 ] || { fail 'Usage: configure NODE ON OFF ONLINE_NODE'; return 1; }
    [ ! -e "$STATE/journal" ] || { fail 'Turn bypass off before changing profile'; return 1; }
    native_path "$1" && boolean "$2" && boolean "$3" && [ "$2" != "$3" ] && online_path "$4" || {
        fail 'Only explicit native bypass boolean nodes are supported; charging/input suspend nodes are rejected'; return 1;
    }
    [ -r "$SYSROOT$1" ] && [ -w "$SYSROOT$1" ] && [ -r "$SYSROOT$4" ] || { fail 'Configured nodes are unavailable'; return 1; }
    boolean "$(value "$1")" && boolean "$(value "$4")" || { fail 'Configured node values are not boolean'; return 1; }
    BACKEND=native-boolean NODE=$1 ON=$2 OFF=$3 ONLINE=$4 MIN=30 MAX=450
    write_config || return 1
    log "Profile configured: $NODE; no hardware writes performed"
    printf '%s\n' 'Profile saved. Verify driver semantics before using on. Limits: capacity >=30%, temperature <45 C.'
}
configure_mca() {
    [ "$#" = 0 ] || { fail 'Usage: configure-mca'; return 1; }
    [ ! -e "$STATE/journal" ] || { fail 'Restore the active session before changing profile'; return 1; }
    load_mca && mca_supported || { fail "MCA profile unavailable: ${MCA_WHY:-profile_missing}"; return 1; }
    [ -w "$SYSROOT$MCA_NODE" ] || { fail 'MCA request node is not writable'; return 1; }
    BACKEND=xiaomi-mca-policy NODE=$MCA_NODE ON=1 OFF=0 ONLINE=$MCA_USB MIN=30 MAX=450
    write_config || return 1
    log 'Vendor FCC-limit profile configured; no hardware writes performed'
    printf '%s\n' 'Vendor FCC-limit profile saved; power_path=unverified. No request enabled.'
}
limits() {
    [ "$#" = 2 ] && number "$1" && number "$2" || { fail 'Usage: limits MIN_CAPACITY MAX_TEMP_DECI_C'; return 1; }
    [ ! -e "$STATE/journal" ] || { fail 'Turn bypass off before changing limits'; return 1; }
    load_config || return 1
    [ "$1" -ge 10 ] && [ "$1" -le 95 ] && [ "$2" -ge 300 ] && [ "$2" -le 500 ] || { fail 'Allowed limits: capacity 10..95%, temperature 300..500 deci-C'; return 1; }
    MIN=$1 MAX=$2
    write_config
}
heartbeat() {
    printf 'boot=%s\npid=%s\ntime=%s\n' "$BOOT" "$$" "$(clock_now)" > "$STATE/watchdog.tmp" && mv "$STATE/watchdog.tmp" "$STATE/watchdog"
}
tick() {
    if [ -e "$STATE/recovery_pending" ]; then
        restore || return 1
        rm -f "$STATE/recovery_pending" || return 1
    fi
    if [ -e "$STATE/journal" ]; then
        if ! load_config || ! journal_matches_config || ! guard || [ "$(backend_read "$BACKEND" "$NODE")" != "$ON" ]; then
            log 'Guard or controller-state check failed; restoring original state'
            restore || return 1
        fi
    fi
    heartbeat
}
stop_daemon() {
    STOP_RESULT=0
    if lock; then
        STOP_TRIES=0
        while :; do
            if restore; then STOP_RESULT=0; break; fi
            STOP_RESULT=1
            STOP_TRIES=$((STOP_TRIES + 1))
            [ "$STOP_TRIES" -lt 3 ] || break
            sleep 1 9>&-
        done
        if [ "$(field pid "$STATE/watchdog")" = "$$" ]; then
            [ "$STOP_RESULT" != 0 ] || rm -f "$STATE/recovery_pending"
            rm -f "$STATE/watchdog"
        fi
        unlock
    else
        STOP_RESULT=1
    fi
    return "$STOP_RESULT"
}
run_daemon() {
    # Keep recovery code in memory before module removal can unlink its files.
    if [ -r "$MODDIR/profiles/mca.sh" ]; then load_mca || return 1; fi
    lock || return 1
    if watchdog_alive; then unlock; fail 'Watchdog already running'; return 1; fi
    if ! restore; then
        [ -e "$STATE/recovery_pending" ] || { unlock; return 1; }
        log 'Startup recovery pending; watchdog will retry without enabling a new session'
    else
        rm -f "$STATE/recovery_pending" || { unlock; return 1; }
    fi
    heartbeat || { unlock; return 1; }
    unlock
    trap 'stop_daemon; exit $?' INT TERM HUP
    while :; do
        if lock; then
            tick
            if [ ! -d "$MODDIR" ] || [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
                if restore; then
                    rm -f "$STATE/watchdog" "$STATE/recovery_pending"
                    unlock
                    return 0
                fi
            fi
            unlock
        fi
        sleep 5
    done
}
status() {
    CONFIGURED=no SESSION_ACTIVE=no REQUEST_STATE=unknown RAW_NODE_VALUE=unknown MODE=unconfigured POLICY=none STATUS_ERROR=
    RECOVERY_PENDING=no
    STATUS_BOOT_OK=yes
    case "$BOOT" in ''|*[!a-zA-Z0-9-]*) STATUS_BOOT_OK=no STATUS_ERROR=boot_unreadable_or_invalid ;; esac
    if [ ! -x "${STATE%/*}" ] || { [ -d "$STATE" ] && { [ ! -r "$STATE" ] || [ ! -x "$STATE" ]; }; }; then
        CONFIGURED=unknown SESSION_ACTIVE=unknown RECOVERY_PENDING=unknown STATUS_ERROR=state_unreadable
    elif [ -e "$STATE/config" ]; then
        if load_config 2>/dev/null; then
            CONFIGURED=yes MODE=$BACKEND
            if [ "$BACKEND" = xiaomi-mca-policy ]; then POLICY=fcc_limit; else POLICY=native_request; fi
            RAW_NODE_VALUE=$(value "$NODE") || RAW_NODE_VALUE=unknown
            REQUEST=$(backend_read "$BACKEND" "$NODE" 2>/dev/null) || REQUEST=unknown
            if [ "$REQUEST" = "$ON" ]; then REQUEST_STATE=on; elif [ "$REQUEST" = "$OFF" ]; then REQUEST_STATE=off; else STATUS_ERROR=request_unreadable_or_invalid; fi
            printf 'node=%s\nnode_value=%s\non=%s\noff=%s\nonline=%s\nmin_capacity=%s\nmax_temp_deci_c=%s\n' "$NODE" "$RAW_NODE_VALUE" "$ON" "$OFF" "$(value "$ONLINE")" "$MIN" "$MAX"
            if [ "$BACKEND" = xiaomi-mca-policy ]; then
                printf 'board_temp_deci_c=%s\nwireless_online=%s\n' "$(value "$MCA_DIR/board_temp")" "$(value "$MCA_WIRELESS")"
                if ! mca_supported; then STATUS_ERROR=$MCA_WHY; fi
            fi
        else
            CONFIGURED=unknown STATUS_ERROR=config_unreadable_or_invalid
        fi
    fi
    if [ -e "$STATE/journal" ]; then
        RECOVERY_PENDING=yes SESSION_ACTIVE=unknown
        if load_journal 2>/dev/null; then
            JREQUEST=$(backend_read "$JBACKEND" "$JNODE" 2>/dev/null) || JREQUEST=unknown
            if [ "$STATUS_BOOT_OK" != yes ]; then STATUS_ERROR=boot_unreadable_or_invalid
            elif [ "$JBOOT" != "$BOOT" ]; then SESSION_ACTIVE=no
            elif [ "$JREQUEST" = "$JON" ]; then SESSION_ACTIVE=yes
            elif [ "$JREQUEST" = "$JORIG" ]; then SESSION_ACTIVE=no
            else STATUS_ERROR=recovery_node_unreadable_or_invalid
            fi
        else
            STATUS_ERROR=journal_unreadable_or_invalid
        fi
    fi
    if [ -e "$STATE/recovery_pending" ]; then RECOVERY_PENDING=yes STATUS_ERROR=recovery_pending; fi
    printf 'configured=%s\nsession_active=%s\nrequest_state=%s\nraw_node_value=%s\nmode=%s\npolicy=%s\nrecovery_pending=%s\n' "$CONFIGURED" "$SESSION_ACTIVE" "$REQUEST_STATE" "$RAW_NODE_VALUE" "$MODE" "$POLICY" "$RECOVERY_PENDING"
    [ -z "$STATUS_ERROR" ] || printf 'error=%s\n' "$STATUS_ERROR"
    if watchdog_ready 2>/dev/null; then printf 'watchdog=running\n'; else printf 'watchdog=unavailable\n'; fi
    printf 'capacity=%s\ntemp_deci_c=%s\ncurrent_now_raw=%s\npower_path=unverified\n' "$(value /sys/class/power_supply/battery/capacity)" "$(value /sys/class/power_supply/battery/temp)" "$(value /sys/class/power_supply/battery/current_now)"
    return 0
}
probe() {
    MCA_WHY=profile_missing
    if load_mca 2>/dev/null && mca_supported; then MCA_SUPPORTED=yes; else MCA_SUPPORTED=no; fi
    printf 'mca_supported=%s\nwhy=%s\nmode=xiaomi-mca-policy\npolicy=fcc_limit\npower_path=unverified\n' "$MCA_SUPPORTED" "$MCA_WHY"
}
diagnose() {
    printf '%s\n' 'Read-only diagnostics. Node presence does not prove bypass capability.'
    if command -v getprop >/dev/null 2>&1; then
        for PROP in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.incremental; do
            printf '%s=%s\n' "$PROP" "$(getprop "$PROP")"
        done
    fi
    uname -r
    for SUPPLY in "$SYSROOT"/sys/class/power_supply/*; do
        [ -d "$SUPPLY" ] || continue
        for KEY in type online status capacity temp current_now voltage_now charging_enabled charge_disable input_suspend bypass_charging bypass_charging_enable bypass_mode charge_bypass; do
            [ -r "$SUPPLY/$KEY" ] && printf '%s=%s\n' "${SUPPLY#"$SYSROOT"}/$KEY" "$(cat "$SUPPLY/$KEY" 2>/dev/null)"
        done
    done
    for CANDIDATE in "$SYSROOT"/sys/class/qcom-battery/bypass_charging_enable "$SYSROOT"/sys/devices/platform/soc/*/qcom-battery/bypass_charging_enable; do
        [ -r "$CANDIDATE" ] && printf '%s=%s\n' "${CANDIDATE#"$SYSROOT"}" "$(cat "$CANDIDATE" 2>/dev/null)"
    done
    SMART=/sys/devices/platform/soc/soc:smart_charge
    if [ -d "$SYSROOT$SMART" ]; then
        printf '%s\n' 'MCA smart_chg is a command protocol, not a boolean bypass node.'
        for KEY in smart_chg scene board_temp; do
            [ -r "$SYSROOT$SMART/$KEY" ] && printf '%s=%s\n' "$SMART/$KEY" "$(value "$SMART/$KEY")"
        done
        for KEY in smart_bypass_support smart_bypass_entry_soc; do
            if [ -r "$SYSROOT$SMART/of_node/$KEY" ]; then
                printf '%s (big-endian hex)=' "$SMART/of_node/$KEY"
                od -An -tx1 "$SYSROOT$SMART/of_node/$KEY"
            fi
        done
        printf '%s\n' 'power_path=unverified; vendor smart bypass may only apply an FCC limit.'
    fi
    for INVENTORY in "$SYSROOT"/sys/devices/platform/soc/soc:mca_* "$SYSROOT$SMART"; do
        [ -d "$INVENTORY" ] || continue
        printf 'attribute_names=%s\n' "${INVENTORY#"$SYSROOT"}"
        # List names without reading debug/register/ship-mode attributes.
        ls -1 "$INVENTORY" 2>&1
    done
    return 0
}
main() {
    COMMAND=${1:-status}
    [ "$#" -eq 0 ] || shift
    # Diagnostics also work from an unpacked preview and create no state files.
    [ "$COMMAND" != diagnose ] || { diagnose; return $?; }
    [ "$COMMAND" != probe ] || { probe; return $?; }
    if [ "$COMMAND" = status ]; then
        BOOT=$(value /proc/sys/kernel/random/boot_id)
        status
        return 0
    fi
    init || return 1
    case "$COMMAND" in
        run) run_daemon; return $? ;;
    esac
    lock || return 1
    case "$COMMAND" in
        configure) configure "$@" ;;
        configure-mca) configure_mca "$@" ;;
        limits) limits "$@" ;;
        on) enable ;;
        off) restore ;;
        toggle) if [ -f "$STATE/journal" ]; then restore; else enable; fi ;;
        *) fail 'Commands: diagnose, probe, configure-mca, configure NODE ON OFF ONLINE_NODE, limits MIN MAX, on, off, toggle, status' ;;
    esac
    RESULT=$?
    unlock
    return "$RESULT"
}
