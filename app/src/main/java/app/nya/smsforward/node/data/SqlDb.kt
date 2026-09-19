package app.nya.smsforward.node.data

/**
 * The smallest slice of SQLite the app needs. Production runs it on Android's SQLiteDatabase; the unit tests run the very
 * same SQL against a real SQLite through JDBC, so the queries in [SqliteOutbox] are verified without a device.
 */
interface SqlDb {
    /** Runs an INSERT / UPDATE / DELETE / DDL statement and returns the number of rows changed. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (Row) -> T): List<T>

    /** Runs [block] atomically: everything commits together or not at all. */
    fun <T> transaction(block: () -> T): T

    /** PRAGMA user_version, used to version the schema. */
    var version: Int
}

interface Row {
    fun long(index: Int): Long
    fun longOrNull(index: Int): Long?
    fun string(index: Int): String
    fun stringOrNull(index: Int): String?
}
