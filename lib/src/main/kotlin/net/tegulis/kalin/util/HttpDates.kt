package net.tegulis.kalin.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** RFC 9110 HTTP-date formatting and parsing. */
public object HttpDates {
    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

    /** Formats epoch milliseconds as an IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`). */
    @JvmStatic public fun format(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))

    /** Parses an HTTP-date (IMF-fixdate, RFC 850 or asctime). Returns epoch milliseconds or null. */
    @JvmStatic
    public fun parse(value: String): Long? {
        val v = value.trim()
        return try {
            ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: Exception) {
            parseLegacy(v)
        }
    }

    private val rfc850: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)
    private val asctime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US).withZone(ZoneOffset.UTC)

    private fun parseLegacy(v: String): Long? {
        for (f in arrayOf(rfc850, asctime)) {
            try {
                return ZonedDateTime.parse(v, f).toInstant().toEpochMilli()
            } catch (_: Exception) {}
        }
        return null
    }
}
