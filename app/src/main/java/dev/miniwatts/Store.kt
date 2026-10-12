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
        put("readings", readings)
    }

fun JSONObject.sample(): Sample {
    fun i(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null
    fun l(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null
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
    )
}

class Store(context: Context) : SQLiteOpenHelper(context, "miniwatts.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT,start INTEGER,end INTEGER,startLevel INTEGER,endLevel INTEGER,boot INTEGER,lastMono INTEGER,lastWall INTEGER,wh REAL,covered INTEGER,peak REAL,temp INTEGER,recovery INTEGER DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE samples(id INTEGER PRIMARY KEY AUTOINCREMENT,session INTEGER,data TEXT)"
        )
        db.execSQL("CREATE INDEX samples_session ON samples(session,id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
                val peak = listOfNotNull(active.peakW, s.powerW).maxOrNull()
                val temp = listOfNotNull(active.peakDeciC, s.temperatureDeciC).maxOrNull()
                db.execSQL(
                    "UPDATE sessions SET endLevel=?,lastMono=?,lastWall=?,wh=?,covered=?,peak=?,temp=? WHERE id=?",
                    arrayOf(
                        s.level,
                        s.monoMs,
                        s.wallMs,
                        active.energyWh + (segment?.wh ?: 0.0),
                        active.coveredMs + (segment?.coveredMs ?: 0),
                        peak,
                        temp,
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
                "{\"schema\":1,\"boundary\":\"Signed net cell power; no adapter input or efficiency. Aggregates cover observed segments; latest 1500 samples retained.\",\"sessions\":["
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
                            put("peakW", session.peakW ?: JSONObject.NULL)
                            put("averageW", session.averageW ?: JSONObject.NULL)
                            put("peakTemperatureDeciC", session.peakDeciC ?: JSONObject.NULL)
                            put("uncertainEnd", session.uncertainEnd)
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
            "session,start_ms,end_ms,start_pct,end_pct,session_net_cell_wh,session_covered_ms,session_peak_w,session_average_w,wall_ms,monotonic_ms,boot,continuity,level_pct,plugged,status,current_uA,average_uA,voltage_mV,temperature_deciC,charge_uAh,energy_nWh,cycles,thermal,interval_ms,net_cell_W,power_state,source\n"
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
                        )
                        .joinToString(",") { it?.toString() ?: "" }
                )
                writer.write("\n")
            }
        }
    }
}
