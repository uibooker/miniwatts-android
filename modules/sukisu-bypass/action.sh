#!/system/bin/sh
MODDIR=${0%/*}
STATUS=$(/system/bin/sh "$MODDIR/control.sh" status)
STATUS_RESULT=$?
printf '%s\n' "$STATUS"
[ "$STATUS_RESULT" -eq 0 ] || {
    printf '%s\n' 'Status unavailable; no toggle performed. Use WebUI diagnostics or control.sh off for recovery.'
    exit 1
}
status_field() {
    printf '%s\n' "$STATUS" | sed -n "s/^$1=//p"
}
CONFIGURED=$(status_field configured)
SESSION=$(status_field session_active)
REQUEST=$(status_field request_state)
RECOVERY=$(status_field recovery_pending)
MODE=$(status_field mode)
ERROR=$(status_field error)
case "$CONFIGURED" in
    no)
        /system/bin/sh "$MODDIR/control.sh" probe
        printf '%s\n' 'No profile selected. Open WebUI and explicitly select the dada FCC policy, or run:' \
            'sh /data/adb/modules/miniwatts_bypass/control.sh configure-mca' \
            'Configuration does not enable the policy. Hardware bypass remains unverified.'
        exit 1 ;;
    yes) ;;
    *) printf '%s\n' 'Configuration unknown; no toggle performed. Open WebUI diagnostics.'; exit 1 ;;
esac
[ -z "$ERROR" ] && { [ "$REQUEST" = on ] || [ "$REQUEST" = off ]; } &&
    { [ "$RECOVERY" = yes ] || [ "$RECOVERY" = no ]; } || {
    printf '%s\n' 'Request or recovery state is unreliable; no toggle performed. Use WebUI diagnostics / recovery.'
    exit 1
}
case "$MODE" in
    xiaomi-mca-policy) printf '%s\n' 'Action toggles a vendor FCC limiting request; this is not verified hardware bypass.' ;;
    native-boolean) printf '%s\n' 'Action toggles a native interface request; actual hardware power path is unverified.' ;;
    *) printf '%s\n' 'Mode unknown; no toggle performed.'; exit 1 ;;
esac
case "$SESSION" in
    yes) COMMAND=off ;;
    no)
        if [ "$RECOVERY" != no ] || [ "$REQUEST" != off ]; then
                printf '%s\n' 'Recovery or request state is uncertain; no enable performed. Use WebUI off/diagnostics.'
                exit 1
        fi
        COMMAND=on ;;
    *) printf '%s\n' 'Session state unknown; no toggle performed. Use WebUI off/diagnostics.'; exit 1 ;;
esac
/system/bin/sh "$MODDIR/control.sh" "$COMMAND"
RESULT=$?
/system/bin/sh "$MODDIR/control.sh" status
[ "$RESULT" -eq 0 ] || printf '%s\n' 'Action failed. Read the error and current state; do not assume the request was applied or restored.'
exit "$RESULT"
