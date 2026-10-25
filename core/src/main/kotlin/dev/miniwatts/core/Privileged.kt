package dev.miniwatts.core

/**
 * One privileged probe, as returned by the optional Shizuku user service.
 *
 * [uid] and [selinux] describe the identity the probe actually ran with, because that is what
 * decides whether the vendor nodes could be read at all: an ADB-shell Shizuku is refused, a
 * root-backed one succeeds.
 */
data class Privileged(
    val adapter: Adapter,
    val uid: Int?,
    val selinux: String?,
    /** Keys policy refused (EACCES) and keys the device does not expose (ENOENT). */
    val refused: List<String>,
    val missing: List<String>,
    val raw: String,
) {
    /** True when the probe ran as root, which is the only identity that can read these nodes here. */
    val rootBacked: Boolean
        get() = uid == 0
}

/**
 * Parses the `key=value` lines produced by `PrivilegedUserService.probe()`.
 *
 * Values that start with `!` are unavailability markers, never data: `!EACCES` means policy refused
 * the read, `!ENOENT` means the node does not exist on this device. Neither is treated as zero.
 */
object PrivilegedParser {

    fun parse(text: String): Privileged {
        // Kernel proc/sysfs reads can carry NUL padding (for example the SELinux context from
        // /proc/self/attr/current is NUL-terminated). Control characters must never reach the model:
        // they break XML/accessibility dumps and make the exported JSON invalid.
        val clean = text.filter { !it.isISOControl() || it == '\n' || it == '\t' }
        val values = LinkedHashMap<String, String>()
        var uid: Int? = null
        var selinux: String? = null
        clean.lineSequence().forEach { line ->
            val at = line.indexOf('=')
            if (at <= 0) return@forEach
            val key = line.substring(0, at).trim()
            val value = line.substring(at + 1).trim()
            values[key] = value
            when (key) {
                "uid" -> uid = value.toIntOrNull()
                "selinux" -> selinux = value.takeUnless { it.startsWith("!") }
            }
        }
        val refused = values.filterValues { it == "!EACCES" }.keys.toList()
        val missing = values.filterValues { it == "!ENOENT" }.keys.toList()

        fun int(key: String): Int? = values[key]?.toIntOrNull()
        fun text(key: String): String? = values[key]?.takeUnless { it.startsWith("!") }

        val adapter =
            Adapter(
                inputVoltageUv = int("usb.voltage_now"),
                inputCurrentUa = int("usb.current_now"),
                maxCurrentUa = int("usb.current_max"),
                maxVoltageUv = int("usb.voltage_max"),
                realType = text("charger.real_type") ?: text("usb.type"),
                quickChargeType = int("charger.quick_charge_type"),
                advertisedW = int("charger.power_max"),
                powerRole = role(text("typec.power_role")) ?: text("pd.current_pr"),
                dataRole = role(text("typec.data_role")),
                pdState = text("pd.current_state"),
                pdVerified = int("pd.verified"),
                cellCurrentUa = int("battery.current_now"),
                cellVoltageUv = int("battery.voltage_now"),
                fullChargeUah = int("battery.charge_full"),
                designCapacityUah = int("battery.charge_full_design"),
                sohPercent = int("fg.soh_new"),
                source = "Shizuku user service (uid=${uid ?: "?"})",
            )
        return Privileged(adapter, uid, selinux, refused, missing, clean)
    }

    /**
     * The kernel Type-C class prints every supported role and brackets the active one, for example
     * `source [sink]`. Without brackets the first token is the current role.
     */
    fun role(value: String?): String? {
        if (value == null) return null
        val active = Regex("\\[([^]]+)]").find(value)?.groupValues?.get(1)
        return (active ?: value.split(' ', '\t').firstOrNull())
            ?.trim()
            ?.lowercase()
            ?.takeUnless { it.isEmpty() || it == "none" }
    }

    /**
     * Merges the privileged readings into a record that already holds the public and vendor fields.
     * Public and vendor values win for the keys they own, so a stale privileged probe can never
     * overwrite fresher broadcast data. The provenance names every source that contributed, so a
     * value can never be silently attributed to the wrong channel.
     */
    fun merge(public: Adapter?, privileged: Adapter?): Adapter? {
        if (privileged == null) return public
        if (public == null) return privileged
        return public.copy(
            inputVoltageUv = privileged.inputVoltageUv,
            inputCurrentUa = privileged.inputCurrentUa,
            maxCurrentUa = public.maxCurrentUa ?: privileged.maxCurrentUa,
            maxVoltageUv = public.maxVoltageUv ?: privileged.maxVoltageUv,
            realType = privileged.realType ?: public.realType,
            quickChargeType = public.quickChargeType ?: privileged.quickChargeType,
            advertisedW = public.advertisedW ?: privileged.advertisedW,
            powerRole = privileged.powerRole,
            dataRole = privileged.dataRole,
            pdState = privileged.pdState,
            pdVerified = privileged.pdVerified,
            cellCurrentUa = privileged.cellCurrentUa,
            cellVoltageUv = privileged.cellVoltageUv,
            fullChargeUah = privileged.fullChargeUah,
            designCapacityUah = privileged.designCapacityUah,
            sohPercent = privileged.sohPercent,
            source =
                listOfNotNull(public.source, privileged.source)
                    .distinct()
                    .joinToString(" + ")
                    .takeUnless { it.isEmpty() },
        )
    }
}
