package app.nya.smsforward.node.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * [SqlDb] on Android's SQLite. The schema is managed by [Schema] through PRAGMA user_version, so this deliberately does
 * NOT use SQLiteOpenHelper: the helper keeps its own idea of user_version and would bump it behind [Schema]'s back
 * (leaving tables uncreated), or refuse to open a database whose version it does not know.
 */
class AndroidSqlDb(context: Context, name: String = "node.db") : SqlDb {
    private val appContext = context.applicationContext

    // One handle for the whole process; SQLite serializes writers, and WAL lets readers run alongside.
    private val db: SQLiteDatabase by lazy {
        val file = appContext.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
    }

    override fun execute(sql: String, args: List<Any?>): Int =
        db.compileStatement(sql).use { st ->
            args.forEachIndexed { i, a -> bind(st, i + 1, a) }
            st.executeUpdateDelete()
        }

    override fun <T> query(sql: String, args: List<Any?>, map: (Row) -> T): List<T> {
        // rawQuery binds every argument as TEXT, which SQLite only coerces back for some clauses (LIMIT, comparisons on
        // INTEGER columns). Numbers come from our own code, never from user input, so they are inlined as literals and
        // only strings are bound.
        val (text, bound) = inlineNumbers(sql, args)
        db.rawQuery(text, bound).use { c ->
            val row = CursorRow(c)
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(row)
            return out
        }
    }

    override fun <T> transaction(block: () -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val result = block()
            d.setTransactionSuccessful()
            return result
        } finally {
            d.endTransaction()
        }
    }

    override var version: Int
        get() = db.version
        set(value) {
            db.version = value
        }

    companion object {
        /** Replaces each `?` whose argument is a number (or null) with a literal; returns the SQL and the remaining string args. */
        internal fun inlineNumbers(sql: String, args: List<Any?>): Pair<String, Array<String>> {
            val out = StringBuilder(sql.length + 16)
            val bound = ArrayList<String>()
            var next = 0
            for (ch in sql) {
                if (ch != '?') {
                    out.append(ch)
                    continue
                }
                require(next < args.size) { "SQL has more placeholders than the ${args.size} arguments given" }
                when (val a = args[next++]) {
                    null -> out.append("NULL")
                    is Int, is Long -> out.append(a.toString())
                    is String -> {
                        out.append('?')
                        bound += a
                    }
                    else -> error("unsupported SQL argument type ${a::class}")
                }
            }
            require(next == args.size) { "SQL has $next placeholders but ${args.size} arguments were given" }
            return out.toString() to bound.toTypedArray()
        }
    }

    private fun bind(st: android.database.sqlite.SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> st.bindNull(index)
            is Long -> st.bindLong(index, value)
            is Int -> st.bindLong(index, value.toLong())
            is String -> st.bindString(index, value)
            else -> error("unsupported SQL argument type ${value::class}")
        }
    }

    private class CursorRow(private val c: Cursor) : Row {
        override fun long(index: Int) = c.getLong(index)
        override fun longOrNull(index: Int) = if (c.isNull(index)) null else c.getLong(index)
        override fun string(index: Int): String = c.getString(index)
        override fun stringOrNull(index: Int): String? = if (c.isNull(index)) null else c.getString(index)
    }
}
