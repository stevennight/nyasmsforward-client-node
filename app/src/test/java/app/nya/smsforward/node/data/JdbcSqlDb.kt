package app.nya.smsforward.node.data

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** [SqlDb] over a real SQLite (sqlite-jdbc), so the production SQL can be unit-tested on the JVM. */
class JdbcSqlDb(url: String = "jdbc:sqlite::memory:") : SqlDb, AutoCloseable {
    private val conn: Connection = DriverManager.getConnection(url).apply { autoCommit = true }
    private var depth = 0

    override fun execute(sql: String, args: List<Any?>): Int =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeUpdate()
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs ->
                val row = ResultSetRow(rs)
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth++ > 0) return try { block() } finally { depth-- } // nested: join the outer transaction
        conn.autoCommit = false
        try {
            val result = block()
            conn.commit()
            return result
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
            depth--
        }
    }

    override var version: Int
        get() = conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.getInt(1) } }
        set(value) {
            conn.createStatement().use { it.execute("PRAGMA user_version = $value") }
        }

    override fun close() = conn.close()

    private class ResultSetRow(private val rs: ResultSet) : Row {
        // JDBC columns are 1-based, the Row contract is 0-based (like Android's Cursor).
        override fun long(index: Int) = rs.getLong(index + 1)
        override fun longOrNull(index: Int): Long? = rs.getLong(index + 1).takeUnless { rs.wasNull() }
        override fun string(index: Int): String = rs.getString(index + 1)
        override fun stringOrNull(index: Int): String? = rs.getString(index + 1)
    }
}
