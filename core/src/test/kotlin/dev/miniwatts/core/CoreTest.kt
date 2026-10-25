package dev.miniwatts.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun s(t: Long = 0, ua: Int? = 1_000_000, plugged: Int = 1, boot: Long = 1) =
        Sample(
            100_000 + t,
            t,
            boot,
            50,
            plugged,
            2,
            2,
            ua,
            null,
            4000,
            300,
            null,
            null,
            null,
            null,
            1000,
        )

    private fun session() = Session(1, 100000, null, 50, 50, 1, 0, 100000)

    @Test
    fun unitsAndSentinels() {
        assertEquals(4.0, Units.power(1_000_000, 4000)!!, 0.000001)
        assertEquals(-2.0, Units.power(-500_000, 4000)!!, 0.000001)
        assertNull(Units.property(Int.MIN_VALUE))
        assertNull(Units.property(Long.MIN_VALUE))
        assertNull(Units.power(null, 4000))
        assertNull(Units.power(10, 0))
        assertEquals(0.0, Units.power(0, 4000)!!, 0.0)
    }

    @Test
    fun trapezoidAndSignChange() {
        assertEquals(4.0 / 3600, Integration.between(s(), s(1000))!!.wh, 1e-10)
        assertEquals(0.0, Integration.between(s(), s(1000, -1_000_000))!!.wh, 1e-10)
        assertTrue(s(ua = -1).signConflict)
    }

    @Test
    fun gapsNeverIntegrate() {
        assertNull(Integration.between(s(), s(0)))
        assertNull(Integration.between(s(), s(-1)))
        assertNull(Integration.between(s(), s(3001)))
        assertNull(Integration.between(s(), s(1000, null)))
        assertNull(Integration.between(s(), s(1000, boot = 2)))
        assertNull(Integration.between(s(), s(120_000)))
    }

    @Test
    fun plugUnplugAndRecovery() {
        assertEquals(
            Transition(false, false, false),
            Sessions.transition(null, s().copy(plugged = null)),
        )
        assertEquals(
            Transition(false, false, false),
            Sessions.transition(session(), s(1000).copy(plugged = null)),
        )
        assertTrue(Sessions.transition(null, s()).open)
        assertFalse(Sessions.transition(null, s(plugged = 0)).open)
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s(1000)))
        assertEquals(
            Transition(true, false, false),
            Sessions.transition(session(), s(1000, plugged = 0)),
        )
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s(5000)))
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s(3_600_000)))
        assertEquals(
            Transition(true, false, true),
            Sessions.transition(session(), s(3_600_000, plugged = 0)),
        )
        assertEquals(
            Transition(true, true, true),
            Sessions.transition(session(), s(1000, boot = 2)),
        )
    }

    @Test
    fun measuredAverageUsesCoverage() {
        assertNull(session().averageW)
        assertEquals(2.0, session().copy(energyWh = 0.5, coveredMs = 900000).averageW!!, 1e-10)
    }

    @Test
    fun intervalBoundariesAndClockReset() {
        assertNull(Integration.between(s(), s(1000).copy(continuity = 1)))
        assertNotNull(Integration.between(s(), s(3000)))
        assertNotNull(
            Integration.between(s().copy(intervalMs = 30_000), s(60_000).copy(intervalMs = 30_000))
        )
        assertNull(
            Integration.between(s().copy(intervalMs = 30_000), s(60_001).copy(intervalMs = 30_000))
        )
        assertEquals(4.0 / 3600, Integration.between(s(), s(1000).copy(wallMs = 0))!!.wh, 1e-10)
        assertEquals(
            Transition(true, true, true),
            Sessions.transition(session().copy(lastMonoMs = 2000), s(1000)),
        )
        assertEquals(Transition(false, false, false), Sessions.transition(session(), s()))
    }

    @Test
    fun adapterCeilingIsAContractNotAMeasurement() {
        val charger = Adapter(maxCurrentUa = 1_500_000, maxVoltageUv = 5_000_000)
        assertEquals(7.5, charger.ceilingW!!, 1e-9)
        assertNull(charger.inputW)
        assertTrue(charger.available)
        // A zero or negative limit is not a reading.
        assertNull(Adapter(maxCurrentUa = 0, maxVoltageUv = 5_000_000).ceilingW)
        assertNull(Adapter(maxCurrentUa = 1_500_000).ceilingW)
        assertFalse(Adapter().available)
    }

    @Test
    fun inputPowerUsesMagnitudeAndRole() {
        val rail = Adapter(inputVoltageUv = 4_766_000, inputCurrentUa = 1_387_000)
        assertEquals(6.61, rail.inputW!!, 0.005)
        assertEquals(6.61, rail.copy(inputCurrentUa = -1_387_000).inputW!!, 0.005)
        assertNull(rail.copy(inputVoltageUv = 0).inputW)
        assertNull(rail.copy(inputCurrentUa = null).inputW)
    }

    @Test
    fun capacityHealthNeedsBothCapacities() {
        assertEquals(
            98.83,
            Adapter(fullChargeUah = 5_337_000, designCapacityUah = 5_400_000)
                .capacityHealthPct!!,
            0.01,
        )
        assertNull(Adapter(fullChargeUah = 5_337_000).capacityHealthPct)
        assertNull(
            Adapter(fullChargeUah = 5_337_000, designCapacityUah = 0).capacityHealthPct
        )
    }

    @Test
    fun loadAndCellShareFollowTheTrustedInput() {
        val charging = s().copy(adapter = Adapter(inputVoltageUv = 4_766_000, inputCurrentUa = 1_387_000))
        assertEquals(6.61 - 4.0, charging.loadW!!, 0.005)
        assertEquals(4.0 / 6.61 * 100, charging.cellSharePct!!, 0.05)
        assertNull(charging.partialInputW)
        assertEquals(6.61, charging.trustedInputW!!, 0.005)

        // No measured input, or unplugged: nothing to derive.
        assertNull(s().loadW)
        assertNull(s().cellSharePct)
        assertNull(charging.copy(plugged = 0).loadW)

        // A cell that is being drained has no "share that reached the cell", but the load figure is
        // still defined and now includes what the cell contributed.
        val discharging = charging.copy(currentUa = -1_000_000)
        assertNull(discharging.cellSharePct)
        assertEquals(6.61 + 4.0, discharging.loadW!!, 0.005)

        // A cell figure far above the port figure means the port reading is short: it is kept as
        // evidence, and nothing is derived from it.
        val short = charging.copy(currentUa = 3_000_000)
        assertEquals(6.61, short.partialInputW!!, 0.005)
        assertNull(short.trustedInputW)
        assertNull(short.loadW)
        assertNull(short.cellSharePct)

        // A brief overshoot inside the margins is rail timing, not a short input, and the share is
        // capped rather than reported above 100 %.
        val brief = charging.copy(currentUa = 1_800_000)
        assertNull(brief.partialInputW)
        assertEquals(100.0, brief.cellSharePct!!, 1e-9)
        // Nothing was observed as load or loss during that instant: the port figure is below the
        // cell figure, so there is no remainder to report rather than a remainder of zero.
        assertNull(brief.loadW)
    }

    @Test
    fun privilegedParserSeparatesRefusalFromAbsence() {
        val probe =
            PrivilegedParser.parse(
                """
                uid=0
                selinux=u:r:ksu:s0
                usb.voltage_now=4766000
                usb.current_now=1387000
                charger.real_type=PD
                typec.power_role=source [sink]
                typec.data_role=host [device]
                pd.current_state=SNK_Ready
                pd.verified=0
                battery.charge_full=5337000
                battery.charge_full_design=5400000
                usb.type=!EACCES
                fg.soh_new=!ENOENT
                """
                    .trimIndent()
            )
        assertEquals(0, probe.uid)
        assertTrue(probe.rootBacked)
        assertEquals(listOf("usb.type"), probe.refused)
        assertEquals(listOf("fg.soh_new"), probe.missing)
        assertEquals(4_766_000, probe.adapter.inputVoltageUv)
        assertEquals("PD", probe.adapter.realType)
        assertEquals("sink", probe.adapter.powerRole)
        assertEquals("device", probe.adapter.dataRole)
        assertEquals("SNK_Ready", probe.adapter.pdState)
        assertEquals(98.83, probe.adapter.capacityHealthPct!!, 0.01)
        // Markers are unavailability, never data.
        assertNull(probe.adapter.sohPercent)
    }

    @Test
    fun shellProbeIsRecognisedAsNotRootBacked() {
        val probe =
            PrivilegedParser.parse(
                "uid=2000\nselinux=u:r:shell:s0\nusb.voltage_now=!EACCES\n"
            )
        assertEquals(2000, probe.uid)
        assertFalse(probe.rootBacked)
        assertNull(probe.adapter.inputVoltageUv)
        assertFalse(probe.adapter.available)
    }

    @Test
    fun mergeKeepsBroadcastValuesForTheirOwnKeys() {
        val declared = Adapter(maxCurrentUa = 1_500_000, maxVoltageUv = 5_000_000, source = "broadcast")
        val privileged =
            Adapter(
                maxCurrentUa = 9,
                maxVoltageUv = 9,
                inputVoltageUv = 4_800_000,
                inputCurrentUa = 1_000_000,
                realType = "PD",
                quickChargeType = 1,
                source = "shizuku",
            )
        val merged = PrivilegedParser.merge(declared, privileged)!!
        // The broadcast wins for the ceiling; privileged data fills only what it owns.
        assertEquals(1_500_000, merged.maxCurrentUa)
        assertEquals(5_000_000, merged.maxVoltageUv)
        assertEquals(4_800_000, merged.inputVoltageUv)
        assertEquals(1, merged.quickChargeType)
        // Both channels are named, so no value can be attributed to the wrong source.
        assertEquals("broadcast + shizuku", merged.source)
        assertEquals(declared, PrivilegedParser.merge(declared, null))
        assertEquals(privileged, PrivilegedParser.merge(null, privileged))
        assertNull(PrivilegedParser.merge(null, null))
    }

    @Test
    fun roleParsingHandlesKernelFormat() {
        assertEquals("sink", PrivilegedParser.role("source [sink]"))
        assertEquals("source", PrivilegedParser.role("source [source]"))
        assertEquals("source", PrivilegedParser.role("source"))
        assertNull(PrivilegedParser.role(null))
        assertNull(PrivilegedParser.role("none"))
    }

    /**
     * Fixture taken verbatim from a root-backed probe on Xiaomi 15 (dada, Android 16): the same
     * nodes, the same units. It pins the interpretation so a future whitelist edit cannot silently
     * change what the numbers mean.
     */
    /**
     * Every privileged-only field must survive the merge. A field added to [Adapter] but forgotten
     * here is dropped silently and looks exactly like "the device does not expose it".
     */
    @Test
    fun mergeCarriesEveryPrivilegedField() {
        val privileged =
            Adapter(
                inputVoltageUv = 5_000_000,
                inputCurrentUa = 1_000_000,
                cellCurrentUa = 250_000,
                cellVoltageUv = 4_200_000,
                realType = "PD",
                fullChargeUah = 5_337_000,
                designCapacityUah = 5_400_000,
                sohPercent = 100,
                source = "shizuku",
            )
        val merged = PrivilegedParser.merge(Adapter(maxCurrentUa = 1_500_000), privileged)!!
        assertEquals(250_000, merged.cellCurrentUa)
        assertEquals(4_200_000, merged.cellVoltageUv)
        assertEquals(5_000_000, merged.inputVoltageUv)
        assertEquals(5_337_000, merged.fullChargeUah)
        assertEquals(1_500_000, merged.maxCurrentUa)
        // A cell current of 0 is a real reading and must not be treated as absent.
        assertEquals(0, PrivilegedParser.merge(null, privileged.copy(cellCurrentUa = 0))!!.cellCurrentUa)
    }

    @Test
    fun deviceProbeFixtureMapsToExpectedWattage() {
        val probe =
            PrivilegedParser.parse(
                """
                uid=0
                selinux=u:r:ksu:s0
                usb.voltage_now=4766000
                usb.current_now=1387000
                usb.current_max=1500000
                usb.voltage_max=5000000
                usb.type=USB_PD
                usb.input_current_limit=0
                battery.charge_full=5337000
                battery.charge_full_design=5400000
                charger.real_type=PD
                charger.quick_charge_type=1
                charger.power_max=0
                fg.soh_new=100
                typec.power_role=source [sink]
                typec.data_role=host [device]
                typec.power_operation_mode=usb_power_delivery
                pd.current_state=SNK_Ready
                pd.current_pr=sink
                pd.verified=0
                pd.adapter_id=00000000
                """
                    .trimIndent()
            )
        assertEquals(0, probe.uid)
        assertTrue(probe.rootBacked)
        assertEquals(6.61, probe.adapter.inputW!!, 0.005)
        assertEquals(7.5, probe.adapter.ceilingW!!, 1e-9)
        // The vendor charger node wins over the generic USB type.
        assertEquals("PD", probe.adapter.realType)
        assertEquals(1, probe.adapter.quickChargeType)
        assertEquals(0, probe.adapter.advertisedW)
        assertEquals(100, probe.adapter.sohPercent)
        assertEquals("sink", probe.adapter.powerRole)
        assertEquals(98.83, probe.adapter.capacityHealthPct!!, 0.01)
        assertTrue(probe.refused.isEmpty())
        assertTrue(probe.missing.isEmpty())
    }

    /** An ADB-shell Shizuku on the same device: the nodes exist but policy refuses every one. */
    @Test
    fun shellProbeOnSameDeviceRefusesEveryNode() {
        val probe =
            PrivilegedParser.parse(
                """
                uid=2000
                selinux=u:r:shell:s0
                usb.voltage_now=!EACCES
                usb.current_now=!EACCES
                usb.current_max=!EACCES
                usb.voltage_max=!EACCES
                usb.type=!EACCES
                battery.charge_full=!EACCES
                battery.charge_full_design=!EACCES
                charger.real_type=!EACCES
                typec.power_role=!EACCES
                pd.current_state=!EACCES
                """
                    .trimIndent()
            )
        assertFalse(probe.rootBacked)
        assertFalse(probe.adapter.available)
        assertNull(probe.adapter.inputW)
        assertNull(probe.adapter.capacityHealthPct)
        assertEquals(10, probe.refused.size)
        assertTrue(probe.missing.isEmpty())
    }

    /**
     * `/sys/class/thermal` also carries entries that are not temperatures. Treating them as °C would
     * print "battery 4306 °C", so they must be dropped by name before any range check.
     */
    @Test
    fun thermalZoneNamesThatAreNotTemperatures() {
        assertFalse(Thermal.isTemperature("vbat"))
        assertFalse(Thermal.isTemperature("pmih010x-ibat-lvl0"))
        assertFalse(Thermal.isTemperature("pm8550-bcl-lvl2"))
        assertFalse(Thermal.isTemperature("socd"))
        assertTrue(Thermal.isTemperature("battery"))
        assertTrue(Thermal.isTemperature("charger_therm0"))
        assertTrue(Thermal.isTemperature("cpu-0-1-1"))
        assertNull(Thermal.zone("vbat", "4306"))
        assertNull(Thermal.zone("socd", "23"))
    }

    /** Real values from Xiaomi 15 (dada): millidegrees, an absent sentinel, and garbage. */
    @Test
    fun thermalValueConversionHandlesSentinelAndRange() {
        assertEquals(312, Thermal.deciCelsius("31200"))
        assertEquals(275, Thermal.deciCelsius("27500"))
        // 1 deci-degree is 100 millidegrees, so -500 milli is -0.5 °C.
        assertEquals(-5, Thermal.deciCelsius("-500"))
        assertNull(Thermal.deciCelsius("-273000"))
        assertNull(Thermal.deciCelsius("999999"))
        assertNull(Thermal.deciCelsius("n/a"))
        assertNull(Thermal.deciCelsius(""))
        // Absent zones keep their raw value for diagnostics but never become a reading.
        val zone = Thermal.zone("sdr0", "-273000")!!
        assertEquals("sdr0", zone.name)
        assertNull(zone.deciC)
        assertEquals("-273000", zone.raw)
    }

    @Test
    fun thermalGroupingCoversTheNamedDeviceZones() {
        assertEquals(ThermalGroup.BATTERY, Thermal.group("battery"))
        assertEquals(ThermalGroup.CHARGER, Thermal.group("charger_therm0"))
        assertEquals(ThermalGroup.CHARGER, Thermal.group("conn_therm2"))
        assertEquals(ThermalGroup.CPU, Thermal.group("cpu-0-3-1"))
        assertEquals(ThermalGroup.CPU, Thermal.group("cpuss-1-0"))
        assertEquals(ThermalGroup.GPU, Thermal.group("gpuss-7"))
        assertEquals(ThermalGroup.PMIC, Thermal.group("pmih010x_tz"))
        assertEquals(ThermalGroup.PMIC, Thermal.group("pm8550ve_f_tz"))
        assertEquals(ThermalGroup.MODEM, Thermal.group("nsphmx-1"))
        assertEquals(ThermalGroup.DISPLAY, Thermal.group("display_therm"))
        assertEquals(ThermalGroup.WIRELESS, Thermal.group("wifi_therm"))
        assertEquals(ThermalGroup.WIRELESS, Thermal.group("pa_therm1"))
        assertEquals(ThermalGroup.CAMERA, Thermal.group("video"))
        assertEquals(ThermalGroup.OTHER, Thermal.group("xo-therm"))
    }

    @Test
    fun thermalSnapshotAggregatesPerGroupAndCooling() {
        val snapshot =
            ThermalSnapshot(
                zones =
                    listOf(
                        Thermal.zone("cpu-0-0-0", "33200")!!,
                        Thermal.zone("cpu-0-1-1", "74900")!!,
                        Thermal.zone("gpuss-0", "31400")!!,
                        Thermal.zone("battery", "27600")!!,
                        Thermal.zone("sdr0", "-273000")!!,
                    ),
                cooling =
                    listOf(
                        CoolingDevice("cpu-hotplug0", 0, 1),
                        CoolingDevice("cpufreq-cpu0", 3, 15),
                        CoolingDevice("pause-cpu1", null, 1),
                    ),
                cpu = listOf(CpuPolicy("policy0", 2400000, 3532800)),
                skipped = listOf("vbat"),
                denied = listOf("wireless"),
            )
        assertTrue(snapshot.available)
        assertEquals(4, snapshot.measured.size)
        assertEquals("cpu-0-1-1", snapshot.hottest(ThermalGroup.CPU)!!.name)
        assertEquals(749, snapshot.hottest(ThermalGroup.CPU)!!.deciC)
        assertEquals("gpuss-0", snapshot.hottest(ThermalGroup.GPU)!!.name)
        assertNull(snapshot.hottest(ThermalGroup.CAMERA))
        assertEquals(
            listOf(ThermalGroup.BATTERY, ThermalGroup.CPU, ThermalGroup.GPU),
            snapshot.presentGroups,
        )
        assertEquals(listOf("cpufreq-cpu0"), snapshot.activeCooling.map { it.name })
        assertEquals(0.679, snapshot.cpu.first().ratio!!, 0.001)
        assertEquals(listOf("vbat"), snapshot.skipped)
        assertEquals(listOf("wireless"), snapshot.denied)
        assertFalse(ThermalSnapshot.EMPTY.available)
    }

    @Test
    fun thermalFormattingKeepsTheSign() {
        assertEquals("74.9 °C", Thermal.formatDeci(749))
        assertEquals("-5.0 °C", Thermal.formatDeci(-50))
        assertEquals("0.0 °C", Thermal.formatDeci(0))
    }

    /**
     * Real drivers pad values with NUL: `/proc/self/attr/current` returns a NUL-terminated SELinux
     * context, and that byte used to reach the diagnostics text, where it broke XML/accessibility
     * dumps and would have made the exported JSON invalid.
     */
    @Test
    fun controlCharactersNeverReachTheModel() {
        val probe =
            PrivilegedParser.parse(
                "uid=0\nselinux=u:r:ksu:s0\u0000\ncharger.real_type=P\u0000D\nusb.voltage_now=4766000\u0000\n"
            )
        assertEquals("u:r:ksu:s0", probe.selinux)
        assertEquals("PD", probe.adapter.realType)
        assertEquals(4_766_000, probe.adapter.inputVoltageUv)
        assertFalse(probe.raw.contains('\u0000'))
        assertTrue(probe.raw.lines().isNotEmpty())
        // Newlines must survive so the key/value structure is preserved.
        assertEquals(4, probe.raw.trim().lines().size)
    }

    private fun charged(level: Int, chargeUah: Int?) =
        s().copy(level = level, chargeUah = chargeUah)

    @Test
    fun capacityEstimateNeedsAUsableSpan() {
        // 20 points of charge over 1000 mAh finished 5 Ah of full charge: rejected, too little span.
        assertNull(Capacity.estimate(listOf(charged(50, 1_000_000), charged(60, 1_500_000))))
        // A real span in the constant-current region: 20 points for 1080 mAh -> 5400 mAh.
        val estimate =
            Capacity.estimate(listOf(charged(40, 2_000_000), charged(60, 3_080_000)), sessionId = 9)
        assertNotNull(estimate)
        assertEquals(5_400_000L, estimate!!.fullUah)
        assertEquals(20, estimate.levelDelta)
        assertEquals(9L, estimate.sessionId)
        // A counter that did not move, or moved backwards, can not produce a capacity.
        assertNull(Capacity.estimate(listOf(charged(40, 2_000_000), charged(60, 2_000_000))))
        assertNull(Capacity.estimate(listOf(charged(40, 3_000_000), charged(80, 2_000_000))))
        // Missing endpoints are skipped rather than treated as zero.
        assertNull(Capacity.estimate(listOf(charged(40, null), charged(90, null))))
    }

    @Test
    fun capacityEstimateUsesTheWidestUsableSpan() {
        val samples =
            listOf(
                charged(30, null),
                charged(40, 1_000_000),
                charged(50, 1_540_000),
                charged(60, 2_080_000),
                charged(70, null),
            )
        // The usable endpoints are 40% -> 60%, not the first and last sample.
        assertEquals(5_400_000L, Capacity.estimate(samples)!!.fullUah)
    }

    @Test
    fun capacityPlausibilityFollowsTheDesignCapacityWhenKnown() {
        assertTrue(Capacity.plausible(5_337_000, 5_400_000))
        assertFalse(Capacity.plausible(2_000_000, 5_400_000))
        assertFalse(Capacity.plausible(9_000_000, 5_400_000))
        // Without a design capacity only a defensible absolute band applies.
        assertTrue(Capacity.plausible(5_337_000, null))
        assertFalse(Capacity.plausible(100_000, null))
        assertFalse(Capacity.plausible(50_000_000, null))
    }

    @Test
    fun capacityMedianResistsOneBadSession() {
        assertNull(Capacity.median(emptyList()))
        assertEquals(5_400_000L, Capacity.median(listOf(5_400_000)))
        assertEquals(5_400_000L, Capacity.median(listOf(9_000_000, 5_400_000, 5_300_000)))
        assertEquals(5_350_000L, Capacity.median(listOf(5_400_000, 5_300_000)))
    }

    @Test
    fun inputEnergyIsIntegratedSeparatelyFromTheCell() {
        val adapter = Adapter(inputVoltageUv = 5_000_000, inputCurrentUa = 2_000_000)
        val a = s(ua = 2_000_000).copy(adapter = adapter)
        val b = s(t = 2000, ua = 2_000_000).copy(adapter = adapter)
        val cell = Integration.between(a, b)!!
        val input = Integration.betweenInput(a, b)!!
        assertEquals(2_000L, cell.coveredMs)
        assertEquals(cell.coveredMs, input.coveredMs)
        // 10 W for 2 s = 5.556 mWh of input, 8 W for 2 s = 4.444 mWh into the cell.
        assertEquals(0.005556, input.wh, 1e-5)
        assertEquals(0.004444, cell.wh, 1e-5)
        assertTrue(input.wh > cell.wh)
        // No measured input at either end means no input segment at all, never a zero.
        assertNull(Integration.betweenInput(a, s(t = 2000, ua = 2_000_000)))
        assertNull(Integration.betweenInput(s(t = 2000, ua = 2_000_000), b))
        // The continuity rule applies to the input exactly as it does to the cell.
        val far = s(t = 90_000, ua = 2_000_000).copy(adapter = adapter)
        assertNull(Integration.betweenInput(a, far))
    }

    @Test
    fun sessionShareNeedsComparableCoverage() {
        val base =
            session().copy(
                energyWh = 0.8,
                coveredMs = 100_000,
                inputWh = 1.0,
                inputCoveredMs = 100_000,
            )
        assertTrue(base.hasInputCoverage)
        assertEquals(80.0, base.cellShareOfInputPct!!, 1e-9)
        assertEquals(36.0, base.inputAverageW!!, 1e-9)
        // Input measured over less than half of the cell window is not a comparable ratio.
        assertNull(base.copy(inputCoveredMs = 40_000).cellShareOfInputPct)
        assertEquals(80.0, base.copy(inputCoveredMs = 50_000).cellShareOfInputPct!!, 1e-9)
        // Unmeasured input, zero input energy or an empty cell window all withhold the ratio.
        assertNull(base.copy(inputCoveredMs = 0, inputWh = 0.0).cellShareOfInputPct)
        assertFalse(base.copy(inputCoveredMs = 0, inputWh = 0.0).hasInputCoverage)
        assertNull(base.copy(inputWh = 0.0).cellShareOfInputPct)
        assertNull(base.copy(coveredMs = 0).cellShareOfInputPct)
        // A session whose cell was net discharged has no share: the charger did not deliver energy
        // into the cell, so "how much of the input reached the cell" is not a share at all. Its own
        // two figures (net cell energy and delivered input energy) are still reported.
        assertNull(base.copy(energyWh = -0.2).cellShareOfInputPct)
        assertEquals(-0.2, base.copy(energyWh = -0.2).energyWh, 1e-9)
        // The flag records that the platform's charge state and the cell's direction disagreed; it
        // does not by itself withhold the share of a session that did net-charge.
        assertEquals(80.0, base.copy(signConflictObserved = true).cellShareOfInputPct!!, 1e-9)
    }

    @Test
    fun comparisonKeepsUnavailableValuesOutOfTheNumbers() {
        val charged =
            session().copy(
                id = 4,
                startLevel = 40,
                endLevel = 80,
                energyWh = 12.0,
                coveredMs = 3_600_000,
                peakW = 20.0,
                peakDeciC = 410,
            )
        val same = SessionComparison.of(charged, charged.copy(id = 5))
        assertEquals(12.0, same.left.energyWh, 1e-9)
        assertEquals(0.0, same.energyDeltaWh, 1e-9)
        assertEquals(40, same.left.levelDelta)
        assertTrue(same.ratioComparable)

        // A missing level endpoint yields no delta at all, rather than treating it as 0 %.
        assertNull(SessionComparison.side(charged.copy(endLevel = null)).levelDelta)
        assertNull(SessionComparison.side(charged.copy(startLevel = null)).levelDelta)

        // An unmeasured input stays unavailable even though the stored energy is a valid-looking 0.
        val noInput = SessionComparison.side(charged.copy(inputWh = 0.0, inputCoveredMs = 0))
        assertFalse(noInput.hasInputCoverage)
        assertNull(noInput.measuredInputWh)
        assertNull(noInput.inputAverageW)

        // Measured but genuinely zero input is still a measurement, not a missing one.
        val measuredZero =
            SessionComparison.side(charged.copy(inputWh = 0.0, inputCoveredMs = 60_000))
        assertTrue(measuredZero.hasInputCoverage)
        assertEquals(0.0, measuredZero.measuredInputWh!!, 1e-9)
    }

    @Test
    fun comparisonWithholdsRatiosUntilBothWindowsAreComparable() {
        val base =
            session().copy(
                energyWh = 1.0,
                coveredMs = 100_000,
                inputWh = 1.25,
                inputCoveredMs = 100_000,
            )
        // Half the window is the documented gate, and it is inclusive.
        val half = SessionComparison.of(base, base.copy(id = 2, coveredMs = 50_000))
        assertEquals(0.5, half.coverageBalance!!, 1e-9)
        assertTrue(half.ratioComparable)

        // A third of the window is not a comparable pair: no ratio may be claimed.
        val third = SessionComparison.of(base, base.copy(id = 2, coveredMs = 30_000))
        assertFalse(third.ratioComparable)
        // The absolute difference is still a subtraction of two integrals over their own windows,
        // so it stays available even though no ratio does.
        assertEquals(0.0, third.energyDeltaWh, 1e-9)
        val smaller = SessionComparison.of(base, base.copy(id = 2, energyWh = 0.25))
        assertEquals(0.75, smaller.energyDeltaWh, 1e-9)
        assertTrue(smaller.ratioComparable)

        // An uncovered window kills the ratio in either direction and never divides by zero.
        assertNull(SessionComparison.of(base, base.copy(id = 2, coveredMs = 0)).coverageBalance)
        assertFalse(SessionComparison.of(base, base.copy(id = 2, coveredMs = 0)).ratioComparable)
        assertFalse(SessionComparison.of(base.copy(coveredMs = 0), base).ratioComparable)
    }

    @Test
    fun exportCatalogueIsCompleteAndNamingIsTotal() {
        val entries = ExportCatalog.catalog()
        assertEquals(ExportKind.values().size, entries.size)
        // Every kind is listed exactly once, so no export can become unreachable.
        ExportKind.values().forEach { kind -> assertEquals(kind, ExportCatalog.of(kind).kind) }
        assertEquals(entries.size, entries.map { it.kind }.toSet().size)
        // The document names must be unique, or the SAF picker would offer two identical titles.
        assertEquals(entries.size, entries.map { it.fileName }.toSet().size)
        entries.forEach {
            assertTrue(it.titleZh.isNotBlank())
            assertTrue(it.titleEn.isNotBlank())
            assertTrue(it.mimeType.isNotBlank())
        }
        // History is the pair written from stored sessions; the other two are diagnostics.
        assertTrue(ExportCatalog.isHistory(ExportKind.HISTORY_JSON))
        assertTrue(ExportCatalog.isHistory(ExportKind.HISTORY_CSV))
        assertFalse(ExportCatalog.isHistory(ExportKind.DIAGNOSTICS_JSON))
        assertFalse(ExportCatalog.isHistory(ExportKind.RAW_PROBE_TXT))
    }
}
