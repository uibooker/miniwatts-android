// Copyright-style note: this interface belongs to MiniWatts and is served by PrivilegedUserService.
//
// The Shizuku server instantiates the implementation inside its own process, which runs with the
// identity the user started Shizuku with: the ADB shell (uid 2000) or root (uid 0). That process is
// the only place where vendor power-supply nodes can be read on this device.
//
// Everything here is read-only. The app never asks this service to change device state, and there
// is no method that takes a path or a command from the caller, so the caller cannot widen the
// access this service has.
package dev.miniwatts;

interface IPrivileged {
    /** Reserved method required by the Shizuku server. */
    void destroy() = 16777114;

    /**
     * Reads the app's fixed power-supply whitelist once and returns "key=value" lines.
     *
     * A node that cannot be read is reported as "!REASON" instead of an empty value, so the caller
     * can tell "denied by policy" apart from "this device does not have the node".
     */
    String probe() = 1;

}
