package net.tegulis.kalin.engine

import net.tegulis.kalin.util.HttpDates

/**
 * Caches the `Date` response header line as pre-encoded bytes. The value is refreshed at most once per second by
 * whichever thread first notices that the second has changed; readers never block.
 */
internal object DateCache {
    private class Entry(val second: Long, val line: ByteArray)

    @Volatile private var current: Entry = create(System.currentTimeMillis() / 1000)

    private fun create(second: Long): Entry =
        Entry(second, "Date: ${HttpDates.format(second * 1000)}\r\n".toByteArray(Charsets.ISO_8859_1))

    /** Returns the bytes of `Date: <now>\r\n`. */
    fun dateLine(): ByteArray {
        val now = System.currentTimeMillis() / 1000
        val e = current
        if (e.second == now) return e.line
        val fresh = create(now)
        current = fresh
        return fresh.line
    }
}
