package dev.miniwatts

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.miniwatts.core.*
import java.io.Writer
import org.json.JSONObject

fun Sample.json(): JSONObject =
    JSONObject().apply {
        put("wallMs", wallMs)
        put("monoMs", monoMs)
        put("boot", boot)
        put("level", level)
        put("plugged", plugged)
        put("status", status)
        put("health", health)
        put("currentUa", currentUa)
        put("averageUa", averageUa)
        put("voltageMv", voltageMv)
        put("temperatureDeciC", temperatureDeciC)
        put("chargeUah", chargeUah)
        put("energyNwh", energyNwh)
        put("cycles", cycles)
        put("thermal", thermal)
        put("intervalMs", intervalMs)
        put("continuity", continuity)
        put("powerW", powerW)
        put("powerState", if (powerW == null) "unavailable" else "estimated")
        put("signConflict", signConflict)
        put("source", "ACTION_BATTERY_CHANGED / BatteryManager / PowerManager")
        val readings = JSONObject()
        fun reading(
            key: String,
            value: Any?,
            unit: String,
            source: String,
            estimated: Boolean = false,
        ) {
            put(key, value ?: JSONObject.NULL)
            readings.put(
                key,
                JSONObject().apply {
                    put("value", value ?: JSONObject.NULL)
                    put("unit", unit)
                    put("source", source)
                    put("sampledAtMs", wallMs)
                    put(
                        "state",
                        if (value == null) "unavailable"
                        else if (estimated) "estimated" else "measured",
                    )
                },
            )
        }
        reading("level", level, "%", "ACTION_BATTERY_CHANGED level/scale")
        reading("plugged", plugged, "enum", "ACTION_BATTERY_CHANGED plugged")
        reading("status", status, "enum", "ACTION_BATTERY_CHANGED status")
        reading("health", health, "enum", "ACTION_BATTERY_CHANGED health (not capacity health)")
        reading("currentUa", currentUa, "uA", "BatteryManager CURRENT_NOW")
        reading("averageUa", averageUa, "uA", "BatteryManager CURRENT_AVERAGE")
        reading("voltageMv", voltageMv, "mV", "ACTION_BATTERY_CHANGED voltage")
        reading("temperatureDeciC", temperatureDeciC, "0.1 C", "ACTION_BATTERY_CHANGED temperature")
        reading("chargeUah", chargeUah, "uAh", "BatteryManager CHARGE_COUNTER")
        reading("energyNwh", energyNwh, "nWh", "BatteryManager ENERGY_COUNTER")
        reading("cycles", cycles, "cycles", "ACTION_BATTERY_CHANGED cycle_count (API 34+)")
        reading("thermal", thermal, "enum", "PowerManager currentThermalStatus (API 29+)")
        reading("powerW", powerW, "W", "CURRENT_NOW * battery voltage / 1e9", true)
        val a = adapter
        reading(
            "adapterMaxCurrentUa",
            a?.maxCurrentUa,
            "uA",
            "ACTION_BATTERY_CHANGED max_charging_current (charger ceiling, not measured)",
        )
        reading(
            "adapterMaxVoltageUv",
            a?.maxVoltageUv,
            "uV",
            "ACTION_BATTERY_CHANGED max_charging_voltage (charger ceiling, not measured)",
        )
        reading("adapterCeilingW", a?.ceilingW, "W", "charger ceiling: max current * max voltage", true)
        reading("adapterInputUv", a?.inputVoltageUv, "uV", "Shizuku probe: power_supply/usb voltage_now")
        reading("adapterInputUa", a?.inputCurrentUa, "uA", "Shizuku probe: power_supply/usb current_now")
        reading("adapterInputW", a?.inputW, "W", "adapter input voltage * current", true)
        reading("adapterInvalid", a?.invalid, "0/1", "ACTION_BATTERY_CHANGED invalid_charger")
        reading("quickChargeType", a?.quickChargeType, "enum", "miui ACTION_QUICK_CHARGE_TYPE quick_charge_type")
        reading("adapterAdvertisedW", a?.advertisedW, "W", "miui ACTION_QUICK_CHARGE_TYPE POWER_MAX")
        reading("adapterRealType", a?.realType, "enum", "Shizuku probe: charger real_type")
        reading("powerRole", a?.powerRole, "enum", "Shizuku probe: typec power_role")
        reading("capacityHealthPct", a?.capacityHealthPct, "%", "full charge / design capacity", true)
        put("readings", readings)
        put(
            "adapter",
            a?.let {
                JSONObject().apply {
                    put("maxChargingCurrentUa", it.maxCurrentUa ?: JSONObject.NULL)
                    put("maxChargingVoltageUv", it.maxVoltageUv ?: JSONObject.NULL)
                    put("ceilingW", it.ceilingW ?: JSONObject.NULL)
                    put("inputVoltageUv", it.inputVoltageUv ?: JSONObject.NULL)
                    put("inputCurrentUa", it.inputCurrentUa ?: JSONObject.NULL)
                    put("inputW", it.inputW ?: JSONObject.NULL)
                    // The privileged cell readings are recorded so the cell's own current and
                    // voltage can be analysed afterwards, offline, from the exported samples.
                    put("cellCurrentUa", it.cellCurrentUa ?: JSONObject.NULL)
                    put("cellVoltageUv", it.cellVoltageUv ?: JSONObject.NULL)
                    put("invalidCharger", it.invalid ?: JSONObject.NULL)
                    put("quickChargeType", it.quickChargeType ?: JSONObject.NULL)
                    put("advertisedW", it.advertisedW ?: JSONObject.NULL)
                    put("carCharge", it.carCharge ?: JSONObject.NULL)
                    put("realType", it.realType ?: JSONObject.NULL)
                    put("powerRole", it.powerRole ?: JSONObject.NULL)
                    put("dataRole", it.dataRole ?: JSONObject.NULL)
                    put("pdState", it.pdState ?: JSONObject.NULL)
                    put("pdVerified", it.pdVerified ?: JSONObject.NULL)
                    put("fullChargeUah", it.fullChargeUah ?: JSONObject.NULL)
                    put("designCapacityUah", it.designCapacityUah ?: JSONObject.NULL)
                    put("capacityHealthPct", it.capacityHealthPct ?: JSONObject.NULL)
                    put("sohPercent", it.sohPercent ?: JSONObject.NULL)
                    put("source", it.source ?: JSONObject.NULL)
                }
            } ?: JSONObject.NULL,
        )
        put("loadW", loadW)
        put("cellSharePct", cellSharePct)
        // The raw input stays in the `adapter` object either way; this says whether it was used.
        // A short reading is evidence about the device, so it is exported rather than discarded.
        put("adapterInputTrusted", trustedInputW != null)
        put("adapterInputPartialW", partialInputW ?: JSONObject.NULL)
    }

fun JSONObject.sample(): Sample {
    fun i(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    fun l(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
    val adapter =
        if (has("adapter") && !isNull("adapter")) {
            val o = getJSONObject("adapter")
            fun ai(k: String): Int? = if (o.has(k) && !o.isNull(k)) o.getInt(k) else null
            fun asText(k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
            Adapter(
                    maxCurrentUa = ai("maxChargingCurrentUa"),
                    maxVoltageUv = ai("maxChargingVoltageUv"),
                    inputVoltageUv = ai("inputVoltageUv"),
                    inputCurrentUa = ai("inputCurrentUa"),
                    cellCurrentUa = ai("cellCurrentUa"),
                    cellVoltageUv = ai("cellVoltageUv"),
                    invalid = ai("invalidCharger"),
                    quickChargeType = ai("quickChargeType"),
                    advertisedW = ai("advertisedW"),
                    carCharge = ai("carCharge"),
                    realType = asText("realType"),
                    powerRole = asText("powerRole"),
                    dataRole = asText("dataRole"),
                    pdState = asText("pdState"),
                    pdVerified = ai("pdVerified"),
                    fullChargeUah = ai("fullChargeUah"),
                    designCapacityUah = ai("designCapacityUah"),
                    sohPercent = ai("sohPercent"),
                    source = asText("source"),
                )
                .takeIf { it.available }
        } else null
    return Sample(
        getLong("wallMs"),
        getLong("monoMs"),
        getLong("boot"),
        i("level"),
        i("plugged"),
        i("status"),
        i("health"),
        i("currentUa"),
        i("averageUa"),
        i("voltageMv"),
        i("temperatureDeciC"),
        i("chargeUah"),
        l("energyNwh"),
        i("cycles"),
        i("thermal"),
        getLong("intervalMs"),
        optLong("continuity", 0),
        adapter,
    )
}

class Store(context: Context) : SQLiteOpenHelper(context, "miniwatts.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER,end INTEGER,startLevel INTEGER,endLevel INTEGER,boot INTEGER,lastMono INTEGER,lastWall INTEGER,wh REAL,covered INTEGER,peak REAL,temp INTEGER,recovery INTEGER DEFAULT 0,signConflict INTEGER NOT NULL DEFAULT 0,inputWh REAL NOT NULL DEFAULT 0,inputCovered INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER,data TEXT)"
        )
        db.execSQL("CREATE INDEX samples_session ON samples(session,id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN signConflict INTEGER NOT NULL DEFAULT 0")
            // Old versions can only be checked against the samples they still retain.
            val conflicts = mutableSetOf<Long>()
            db.rawQuery("SELECT session,data FROM samples", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (id !in conflicts && JSONObject(cursor.getString(1)).sample().signConflict)
                        conflicts.add(id)
                }
            }
            conflicts.forEach { id ->
                db.execSQL("UPDATE sessions SET signConflict=1 WHERE id=?", arrayOf(id))
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN inputWh REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE sessions ADD COLUMN inputCovered INTEGER NOT NULL DEFAULT 0")
            // Existing sessions are deliberately left at zero. The input rail was never recorded for
            // them, and a zero here means "not measured": `Session.hasInputCoverage` is what the UI
            // checks, so an old session reports unavailable rather than a 0 Wh input.
        }
    }

    private fun Cursor.session(): Session {
        fun l(i: Int): Long? = if (isNull(i)) null else getLong(i)
        fun n(i: Int): Int? = if (isNull(i)) null else getInt(i)
        return Session(
            getLong(0),
            getLong(1),
            l(2),
            n(3),
            n(4),
            getLong(5),
            getLong(6),
            getLong(7),
            getDouble(8),
            getLong(9),
            if (isNull(10)) null else getDouble(10),
            n(11),
            getInt(12) != 0,
            getInt(13) != 0,
            getDouble(14),
            getLong(15),
        )
    }

    fun sessions(): List<Session> =
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY id DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.session()) }
        }

    fun samples(id: Long, latest: Int? = null): List<Sample> =
        readableDatabase
            .rawQuery(
                if (latest == null) "SELECT data FROM samples WHERE session=? ORDER BY id"
                else "SELECT data FROM samples WHERE session=? ORDER BY id DESC LIMIT ?",
                if (latest == null) arrayOf(id.toString())
                else arrayOf(id.toString(), latest.coerceIn(1, 1500).toString()),
            )
            .use { c ->
                buildList { while (c.moveToNext()) add(JSONObject(c.getString(0)).sample()) }
                    .let { if (latest == null) it else it.reversed() }
            }

    /**
     * The samples a capacity estimate needs: a bounded window at each end of a session, in time
     * order. The charge counter and the level live inside the sample JSON, so this is the cheapest
     * way to reach both endpoints without loading a whole session, which can hold up to 1500 rows.
     */
    fun chargeWindows(id: Long, window: Int = 15): List<Sample> {
        val n = window.coerceIn(1, 100).toString()
        fun query(order: String) =
            readableDatabase
                .rawQuery(
                    "SELECT data FROM samples WHERE session=? ORDER BY id $order LIMIT ?",
                    arrayOf(id.toString(), n),
                )
                .use { c ->
                    buildList { while (c.moveToNext()) add(JSONObject(c.getString(0)).sample()) }
                }
        val head = query("ASC")
        val tail = query("DESC").reversed()
        return if (tail.isEmpty()) head else head + tail
    }

    fun ingest(s: Sample, limit: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var active =
                db.rawQuery(
                        "SELECT * FROM sessions WHERE end IS NULL ORDER BY id DESC LIMIT 1",
                        null,
                    )
                    .use { if (it.moveToFirst()) it.session() else null }
            val change = Sessions.transition(active, s)
            if (change.close) {
                db.execSQL(
                    "UPDATE sessions SET end=?,recovery=? WHERE id=?",
                    arrayOf(
                        if (change.uncertainRecovery) active!!.lastWallMs else s.wallMs,
                        if (change.uncertainRecovery) 1 else 0,
                        active!!.id,
                    ),
                )
                active = null
            }
            if (change.open) {
                db.execSQL(
                    "INSERT INTO sessions(start,startLevel,endLevel,boot,lastMono,lastWall,wh,covered) VALUES(?,?,?,?,?,?,0,0)",
                    arrayOf(s.wallMs, s.level, s.level, s.boot, s.monoMs, s.wallMs),
                )
                active =
                    db.rawQuery(
                            "SELECT * FROM sessions WHERE end IS NULL ORDER BY id DESC LIMIT 1",
                            null,
                        )
                        .use {
                            it.moveToFirst()
                            it.session()
                        }
            }
            if (active != null) {
                val previous =
                    db.rawQuery(
                            "SELECT data FROM samples WHERE session=? ORDER BY id DESC LIMIT 1",
                            arrayOf(active.id.toString()),
                        )
                        .use {
                            if (it.moveToFirst()) JSONObject(it.getString(0)).sample() else null
                        }
                val segment = previous?.let { Integration.between(it, s) }
                val inputSegment = previous?.let { Integration.betweenInput(it, s) }
                val peak = listOfNotNull(active.peakW, s.powerW).maxOrNull()
                val temp = listOfNotNull(active.peakDeciC, s.temperatureDeciC).maxOrNull()
                db.execSQL(
                    "UPDATE sessions SET endLevel=?,lastMono=?,lastWall=?,wh=?,covered=?,peak=?,temp=?,signConflict=?,inputWh=?,inputCovered=? WHERE id=?",
                    arrayOf(
                        s.level,
                        s.monoMs,
                        s.wallMs,
                        active.energyWh + (segment?.wh ?: 0.0),
                        active.coveredMs + (segment?.coveredMs ?: 0),
                        peak,
                        temp,
                        if (active.signConflictObserved || s.signConflict) 1 else 0,
                        active.inputWh + (inputSegment?.wh ?: 0.0),
                        active.inputCoveredMs + (inputSegment?.coveredMs ?: 0),
                        active.id,
                    ),
                )
                db.execSQL(
                    "INSERT INTO samples(session,data) VALUES(?,?)",
                    arrayOf(active.id, s.json().toString()),
                )
                db.execSQL(
                    "DELETE FROM samples WHERE session=? AND id NOT IN (SELECT id FROM samples WHERE session=? ORDER BY id DESC LIMIT 1500)",
                    arrayOf(active.id, active.id),
                )
            }
            trim(limit)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun trim(limit: Int) {
        writableDatabase.execSQL(
            "DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY id DESC LIMIT ?)",
            arrayOf(limit.coerceIn(1, 60)),
        )
        writableDatabase.execSQL(
            "DELETE FROM samples WHERE session NOT IN (SELECT id FROM sessions)"
        )
    }

    fun delete(id: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (id == null) {
                db.delete("samples", null, null)
                db.delete("sessions", null, null)
            } else {
                db.delete("samples", "session=?", arrayOf(id.toString()))
                db.delete("sessions", "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun export(writer: Writer, json: Boolean) {
        val rows = sessions()
        if (json) {
            writer.write(
                "{\"schema\":4,\"boundary\":\"Signed net cell power. Adapter fields are the charger ceiling the platform reports and, only when the optional privileged channel is enabled and actually permitted, the measured input rail. A measured input that comes out below the cell is marked adapterInputTrusted=false and is not used for load, losses or the cell share. Neither is delivered energy, and load/losses stay estimates. Aggregates cover observed segments; latest 1500 samples retained.\",\"sessions\":["
            )
            rows.forEachIndexed { index, session ->
                if (index > 0) writer.write(",")
                val header =
                    JSONObject()
                        .apply {
                            put("id", session.id)
                            put("startMs", session.startMs)
                            put("endMs", session.endMs ?: JSONObject.NULL)
                            put("startLevel", session.startLevel ?: JSONObject.NULL)
                            put("endLevel", session.endLevel ?: JSONObject.NULL)
                            put("netCellWh", session.energyWh)
                            put("coveredMs", session.coveredMs)
                            put("inputWh", if (session.hasInputCoverage) session.inputWh else JSONObject.NULL)
                            put("inputCoveredMs", session.inputCoveredMs)
                            put("cellShareOfInputPct", session.cellShareOfInputPct ?: JSONObject.NULL)
                            put("peakW", session.peakW ?: JSONObject.NULL)
                            put("averageW", session.averageW ?: JSONObject.NULL)
                            put("peakTemperatureDeciC", session.peakDeciC ?: JSONObject.NULL)
                            put("uncertainEnd", session.uncertainEnd)
                            put("signConflictObserved", session.signConflictObserved)
                        }
                        .toString()
                writer.write(header.dropLast(1))
                writer.write(",\"samples\":[")
                samples(session.id).forEachIndexed { sampleIndex, value ->
                    if (sampleIndex > 0) writer.write(",")
                    writer.write(value.json().toString())
                }
                writer.write("]}")
            }
            writer.write("]}")
            return
        }
        writer.write(
            "session,start_ms,end_ms,start_pct,end_pct,session_net_cell_wh,session_covered_ms,session_input_wh,session_input_covered_ms,session_cell_share_of_input_pct,session_peak_w,session_average_w,wall_ms,monotonic_ms,boot,continuity,level_pct,plugged,status,current_uA,average_uA,voltage_mV,temperature_deciC,charge_uAh,energy_nWh,cycles,thermal,interval_ms,net_cell_W,power_state,source,session_sign_conflict_observed,current_sign_conflict,adapter_max_current_uA,adapter_max_voltage_uV,adapter_ceiling_W,adapter_input_uV,adapter_input_uA,adapter_input_W,adapter_invalid,quick_charge_type,adapter_advertised_W,adapter_real_type,typec_power_role,capacity_health_pct,load_W,cell_share_pct,adapter_source,adapter_input_trusted,adapter_input_partial_W\n"
        )
        rows.forEach { session ->
            samples(session.id).forEach { value ->
                writer.write(
                    listOf(
                            session.id,
                            session.startMs,
                            session.endMs,
                            session.startLevel,
                            session.endLevel,
                            session.energyWh,
                            session.coveredMs,
                            if (session.hasInputCoverage) session.inputWh else null,
                            session.inputCoveredMs,
                            session.cellShareOfInputPct,
                            session.peakW,
                            session.averageW,
                            value.wallMs,
                            value.monoMs,
                            value.boot,
                            value.continuity,
                            value.level,
                            value.plugged,
                            value.status,
                            value.currentUa,
                            value.averageUa,
                            value.voltageMv,
                            value.temperatureDeciC,
                            value.chargeUah,
                            value.energyNwh,
                            value.cycles,
                            value.thermal,
                            value.intervalMs,
                            value.powerW,
                            if (value.powerW == null) "unavailable" else "estimated",
                            "Android public battery APIs",
                            session.signConflictObserved,
                            value.signConflict,
                            value.adapter?.maxCurrentUa,
                            value.adapter?.maxVoltageUv,
                            value.adapter?.ceilingW,
                            value.adapter?.inputVoltageUv,
                            value.adapter?.inputCurrentUa,
                            value.adapter?.inputW,
                            value.adapter?.invalid,
                            value.adapter?.quickChargeType,
                            value.adapter?.advertisedW,
                            value.adapter?.realType,
                            value.adapter?.powerRole,
                            value.adapter?.capacityHealthPct,
                            value.loadW,
                            value.cellSharePct,
                            value.adapter?.source,
                            value.trustedInputW != null,
                            value.partialInputW,
                        )
                        .joinToString(",") { it?.toString() ?: "" }
                )
                writer.write("\n")
            }
        }
    }
}
