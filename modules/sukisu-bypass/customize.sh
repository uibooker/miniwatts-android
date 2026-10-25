SKIPMOUNT=true
ui_print "MiniWatts Bypass Control"
ui_print "Default off. Installation does not configure or enable charging policy."
ui_print "WebUI: read-only probe, explicit dada FCC profile, manual on/off."
ui_print "Actual hardware bypass is unverified."
ui_print "After reboot: sh /data/adb/modules/miniwatts_bypass/control.sh probe"
# The manager owns webroot permissions and SELinux context.
set_perm "$MODPATH/control.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
