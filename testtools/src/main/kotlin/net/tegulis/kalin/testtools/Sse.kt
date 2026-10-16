package net.tegulis.kalin.testtools

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A server-sent event received by the test client. */
public data class SseEvent(
    /** The event name, or null for unnamed events. */
    val event: String?,
    /** The data (lines joined with `\n`). */
    val data: String,
    /** The id, or null. */
    val id: String?,
)

/** An open SSE stream; closing it (by leaving the `sse { }` block) disconnects the client. */
public class SseSession
internal constructor(
    private val conn: RawConnection,
    /** The response status. */
    public val status: Int,
    /** The response headers. */
    public val headers: List<Pair<String, String>>,
    body: java.io.InputStream,
) : Closeable {
    private val queue = LinkedBlockingQueue<SseEvent>()
    private val comments = LinkedBlockingQueue<String>()

    @Volatile private var ended = false

    /** True once the server ended the stream. */
    public val isEnded: Boolean
        get() = ended && queue.isEmpty()

    /** The `retry:` value last received, or null. */
    @Volatile
    public var retry: Long? = null
        private set

    init {
        Thread.ofVirtual().name("kalin-test-sse").start {
            try {
                val reader = body.bufferedReader(Charsets.UTF_8)
                var event: String? = null
                var id: String? = null
                val data = StringBuilder()
                var hasData = false
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> {
                            if (hasData) queue.add(SseEvent(event, data.toString(), id))
                            event = null
                            data.setLength(0)
                            hasData = false
                        }
                        line.startsWith(":") -> comments.add(line.substring(1).trimStart())
                        else -> {
                            val field = line.substringBefore(':')
                            val value = line.substringAfter(':', "").removePrefix(" ")
                            when (field) {
                                "event" -> event = value
                                "id" -> id = value
                                "retry" -> retry = value.toLongOrNull()
                                "data" -> {
                                    if (hasData) data.append('\n')
                                    data.append(value)
                                    hasData = true
                                }
                            }
                        }
                    }
                }
            } catch (_: IOException) {} finally {
                ended = true
            }
        }
    }

    /** Waits for [count] events (or until [timeout]) and returns the events received. */
    public fun take(count: Int, timeout: Duration = 5.seconds): List<SseEvent> {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        val result = ArrayList<SseEvent>(count)
        while (result.size < count) {
            val left = deadline - System.nanoTime()
            if (left <= 0) break
            val e = queue.poll(minOf(left, 50_000_000L), TimeUnit.NANOSECONDS)
            if (e != null) result.add(e) else if (ended && queue.isEmpty()) break
        }
        return result
    }

    /** Waits for the next comment line (for example heartbeats). */
    public fun takeComment(timeout: Duration = 5.seconds): String? =
        comments.poll(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    /** Waits until the server ends the stream. Returns true if it ended within [timeout]. */
    public fun awaitEnd(timeout: Duration = 5.seconds): Boolean {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (!ended && System.nanoTime() < deadline) Thread.sleep(5)
        return ended
    }

    /** The first value of response header [name]. */
    public fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, true) }?.second

    /** Disconnects. */
    override fun close() {
        conn.close()
    }
}
