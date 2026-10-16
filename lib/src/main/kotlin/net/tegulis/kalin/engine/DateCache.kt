package net.tegulis.kalin.engine

import net.tegulis.kalin.util.HttpDates

/**
 * Caches the `Date` response header line as pre-encoded bytes. Timer wheels call [refresh] several times per second, so
 * responses never read the clock. Readers never block.
 */
internal object DateCache {
    private class Entry(val second: Long, val line: ByteArray)

    @Volatile private var current: Entry = create(System.currentTimeMillis() / 1000)

    private fun create(second: Long): Entry =
        Entry(second, "Date: ${HttpDates.format(second * 1000)}\r\n".toByteArray(Charsets.ISO_8859_1))

    /** Updates the cached line if the second changed. */
    fun refresh() {
        val second = System.currentTimeMillis() / 1000
        if (current.second != second) current = create(second)
    }

    /** Returns the bytes of `Date: <now>\r\n`. */
    fun dateLine(): ByteArray = current.line
}
