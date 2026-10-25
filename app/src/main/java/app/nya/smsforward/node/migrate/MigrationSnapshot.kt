package app.nya.smsforward.node.migrate

import app.nya.smsforward.node.data.Schema
import app.nya.smsforward.node.data.SqlDb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Everything the old full edition hands to the standard app: its settings, its token, and the rows of node.db.
 * Built on the JSON tree (no generated serializers, so R8 cannot strip anything) and read back in [importTables].
 *
 * Both sides must run the same database [Schema]; the columns are listed here explicitly (ids left out where they are
 * AUTOINCREMENT, so imported rows never collide with rows the standard app already has).
 */
internal object MigrationSnapshot {
    const val FORMAT = 1

    enum class ColumnType { INTEGER, TEXT }

    class Table(val name: String, val columns: List<Pair<String, ColumnType>>, val replace: Boolean, val onlyWhenMovingPairing: Boolean)

    private val I = ColumnType.INTEGER
    private val T = ColumnType.TEXT

    /**
     * What moves. The forwarding state (queue, recent peers, send tasks) belongs to the pairing and only moves with it;
     * the recycle bin and interception are the SMS-app feature's own and always move. The standard app's interception
     * rules are only the built-in defaults (it had no interception before), so they are replaced, not merged.
     */
    val TABLES = listOf(
        Table(
            "outbox",
            listOf(
                "dedupe_key" to T, "peer" to T, "body" to T, "sim_slot" to I, "device_time" to I, "created_at" to I,
                "state" to T, "attempts" to I, "last_error" to T, "finished_at" to I, "direction" to T, "backfill" to I,
                "card_number" to T, "server_id" to I,
            ),
            replace = false, onlyWhenMovingPairing = true,
        ),
        Table("recent_peers", listOf("peer_key" to T, "last_seen_at" to I), replace = false, onlyWhenMovingPairing = true),
        Table(
            "send_tasks",
            listOf(
                "task_id" to T, "recipient" to T, "mode" to T, "state" to T, "error" to T, "started_at" to I,
                "created_at" to I, "updated_at" to I, "body_hash" to T,
            ),
            replace = false, onlyWhenMovingPairing = true,
        ),
        Table(
            "sms_trash",
            listOf(
                "address" to T, "body" to T, "date" to I, "date_sent" to I, "type" to I, "read" to I, "sub_id" to I,
                "origin" to T, "deleted_at" to I,
            ),
            replace = false, onlyWhenMovingPairing = false,
        ),
        Table("block_rules", listOf("kind" to T, "value" to T, "created_at" to I), replace = true, onlyWhenMovingPairing = false),
        Table(
            "sms_blocked",
            listOf(
                "address" to T, "body" to T, "date" to I, "date_sent" to I, "sub_id" to I, "reason" to T, "detail" to T,
                "blocked_at" to I,
            ),
            replace = false, onlyWhenMovingPairing = false,
        ),
    )

    /** The sender's side: [prefs] is SharedPreferences.getAll() of node_settings. */
    fun export(db: SqlDb, prefs: Map<String, *>, token: String?, appVersion: String): String = buildJsonObject {
        put("format", FORMAT)
        put("schema", Schema.LATEST)
        put("version", appVersion)
        put("token", token)
        put("prefs", encodePrefs(prefs))
        put(
            "tables",
            buildJsonObject {
                for (t in TABLES) {
                    val sql = "SELECT ${t.columns.joinToString(", ") { it.first }} FROM ${t.name}"
                    val rows = db.query(sql) { r ->
                        buildJsonArray {
                            t.columns.forEachIndexed { i, (_, type) ->
                                add(if (type == I) JsonPrimitive(r.longOrNull(i)) else JsonPrimitive(r.stringOrNull(i)))
                            }
                        }
                    }
                    put(t.name, JsonArray(rows))
                }
            },
        )
    }.toString()

    class Parsed(val schema: Int, val version: String, val token: String?, val prefs: Map<String, Any>, val tables: JsonObject)

    fun parse(text: String): Parsed {
        val root = Json.parseToJsonElement(text).jsonObject
        require(root["format"]?.jsonPrimitive?.int == FORMAT) { "迁移数据格式不认识" }
        return Parsed(
            schema = root.getValue("schema").jsonPrimitive.int,
            version = root["version"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
            token = root["token"]?.jsonPrimitive?.contentOrNullSafe(),
            prefs = decodePrefs(root["prefs"]?.jsonObject ?: JsonObject(emptyMap())),
            tables = root["tables"]?.jsonObject ?: JsonObject(emptyMap()),
        )
    }

    /** The receiver's side, inside one transaction. Returns rows inserted per table. */
    fun importTables(db: SqlDb, snapshot: Parsed, movingPairing: Boolean): Map<String, Int> = db.transaction {
        Schema.migrate(db)
        val counts = LinkedHashMap<String, Int>()
        for (t in TABLES) {
            if (t.onlyWhenMovingPairing && !movingPairing) continue
            val rows = snapshot.tables[t.name]?.jsonArray ?: continue
            if (t.replace) db.execute("DELETE FROM ${t.name}")
            val sql = "INSERT OR IGNORE INTO ${t.name} (${t.columns.joinToString(", ") { it.first }}) " +
                "VALUES (${t.columns.joinToString(", ") { "?" }})"
            var n = 0
            for (row in rows) {
                val cells = row.jsonArray
                require(cells.size == t.columns.size) { "迁移数据的 ${t.name} 列数不对" }
                val args = t.columns.mapIndexed { i, (_, type) ->
                    val p = cells[i] as? JsonPrimitive
                    when {
                        p == null || p is JsonNull -> null
                        type == I -> p.longOrNull ?: error("迁移数据的 ${t.name} 不是数字")
                        else -> p.content
                    }
                }
                n += db.execute(sql, args)
            }
            counts[t.name] = n
        }
        counts
    }

    // --- SharedPreferences, with the type kept: {"key": {"t": "s"|"b"|"i"|"l"|"f"|"set", "v": ...}} ---

    fun encodePrefs(prefs: Map<String, *>): JsonObject = buildJsonObject {
        for ((key, value) in prefs) {
            val (t, v) = when (value) {
                is String -> "s" to JsonPrimitive(value)
                is Boolean -> "b" to JsonPrimitive(value)
                is Int -> "i" to JsonPrimitive(value)
                is Long -> "l" to JsonPrimitive(value)
                is Float -> "f" to JsonPrimitive(value)
                is Set<*> -> "set" to JsonArray(value.filterIsInstance<String>().sorted().map(::JsonPrimitive))
                else -> continue // null or a type SharedPreferences cannot hold
            }
            put(key, buildJsonObject { put("t", t); put("v", v) })
        }
    }

    fun decodePrefs(json: JsonObject): Map<String, Any> = json.mapNotNull { (key, element) ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val v: JsonElement = o["v"] ?: return@mapNotNull null
        val value: Any? = when (o["t"]?.jsonPrimitive?.contentOrNullSafe()) {
            "s" -> v.jsonPrimitive.contentOrNullSafe()
            "b" -> v.jsonPrimitive.booleanOrNull
            "i" -> v.jsonPrimitive.int
            "l" -> v.jsonPrimitive.long
            "f" -> v.jsonPrimitive.content.toFloatOrNull()
            "set" -> v.jsonArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }.toSet()
            else -> null
        }
        value?.let { key to it }
    }.toMap()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content
}
