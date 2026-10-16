package net.tegulis.kalin.sse

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.System.Logger.Level
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import kotlin.time.Duration
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.http.Header

/** Writes SSE frames (RFC: HTML Living Standard, server-sent events). Thread-safe. */
public class Emitter internal constructor(private val out: OutputStream) {
    /** True once a write failed (the client is gone). */
    @Volatile
    public var closed: Boolean = false
        private set

    private val buffer = java.io.ByteArrayOutputStream(256)

    private fun stripCrLf(value: String): String = value.replace("\r", "").replace("\n", "")

    /** Emits an event with optional [event] name and [id]; each data line becomes a `data:` field. */
    public fun emit(event: String?, data: InputStream, id: String?) {
        synchronized(this) {
            buffer.reset()
            id?.let { buffer.writeUtf8("id: ${stripCrLf(it)}\n") }
            event?.let { buffer.writeUtf8("event: ${stripCrLf(it)}\n") }
            var any = false
            data.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    any = true
                    buffer.writeUtf8("data: $line\n")
                }
            }
            if (!any) buffer.writeAscii("data: \n")
            buffer.writeAscii("\n")
            flushBuffer()
        }
    }

    /** Emits a comment (lines prefixed with `:`). */
    public fun emit(comment: String) {
        synchronized(this) {
            buffer.reset()
            for (line in comment.split(LINE_BREAK)) buffer.writeUtf8(": $line\n")
            buffer.writeAscii("\n")
            flushBuffer()
        }
    }

    /** Emits a `retry:` field telling the browser how long to wait before reconnecting. */
    public fun emitRetry(millis: Long) {
        synchronized(this) {
            buffer.reset()
            buffer.writeAscii("retry: $millis\n\n")
            flushBuffer()
        }
    }

    private fun flushBuffer() {
        if (closed) return
        try {
            buffer.writeTo(out)
            out.flush()
        } catch (_: IOException) {
            closed = true
        }
    }

    private fun java.io.ByteArrayOutputStream.writeAscii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun java.io.ByteArrayOutputStream.writeUtf8(s: String) = write(s.toByteArray(Charsets.UTF_8))

    private companion object {
        /** SSE line terminators: CRLF, a lone CR or a lone LF. */
        val LINE_BREAK = Regex("\r\n|\r|\n")
    }
}

/**
 * A connected server-sent events client. Created by `config.routes.sse(path) { client -> }`. The stream is closed when
 * the handler returns, unless [keepAlive] was called; then it stays open until [close] or the client disconnects.
 */
public class SseClient
internal constructor(private val ctx: Context, private val emitter: Emitter, private val stream: OutputStream) :
    Closeable {
    private val terminated = AtomicBoolean(false)
    private val closeCallbacks = CopyOnWriteArrayList<Runnable>()
    internal val closedFuture = java.util.concurrent.CompletableFuture<Unit>()

    @Volatile
    internal var keepAlive: Boolean = false
        private set

    @Volatile internal var heartbeat: ScheduledFuture<*>? = null

    /** The context of the request that opened the stream. */
    public fun ctx(): Context = ctx

    /** True once the stream was closed by the server or the client. */
    public fun terminated(): Boolean = terminated.get()

    /** The `Last-Event-ID` header sent by a reconnecting browser, or null. */
    public fun lastEventId(): String? = ctx.header(Header.LAST_EVENT_ID)

    /** Keeps the stream open after the handler returns, so events can be sent from elsewhere. */
    public fun keepAlive() {
        keepAlive = true
    }

    /** Adds a callback that runs once when the stream closes (by [close] or client disconnect). */
    public fun onClose(closeCallback: Runnable) {
        closeCallbacks.add(closeCallback)
        // Raced with close(): whoever removes the callback runs it, so it runs exactly once
        if (terminated.get() && closeCallbacks.remove(closeCallback)) runCallback(closeCallback)
    }

    /** Closes the stream. */
    override fun close() {
        if (terminated.getAndSet(true)) return
        heartbeat?.cancel(false)
        try {
            stream.close()
        } catch (_: IOException) {}
        for (cb in closeCallbacks) if (closeCallbacks.remove(cb)) runCallback(cb)
        closedFuture.complete(Unit)
    }

    private fun runCallback(cb: Runnable) {
        try {
            cb.run()
        } catch (e: Exception) {
            log.log(Level.WARNING, "SSE onClose callback failed", e)
        }
    }

    /** Sends [data] as an event named `message`. */
    public fun sendEvent(data: Any): Unit = sendEvent("message", data)

    /** Sends an event. Strings and streams are sent as-is, other objects as JSON. */
    @JvmOverloads public fun sendEvent(event: String, data: Any, id: String? = null): Unit = emitData(event, data, id)

    /** Sends data without an event name (browsers fire the generic `message` event). */
    @JvmOverloads public fun sendData(data: Any, id: String? = null): Unit = emitData(null, data, id)

    /** Sends a comment line (ignored by browsers, useful as heartbeat). */
    public fun sendComment(comment: String) {
        if (terminated.get()) return logTerminated()
        emitter.emit(comment)
        if (emitter.closed) close()
    }

    /** Sends a `retry:` field (reconnection delay in milliseconds). */
    public fun sendRetry(millis: Long) {
        if (terminated.get()) return logTerminated()
        emitter.emitRetry(millis)
        if (emitter.closed) close()
    }

    private fun emitData(event: String?, data: Any, id: String?) {
        if (terminated.get()) return logTerminated()
        when (data) {
            is InputStream -> emitter.emit(event, data, id)
            is String -> emitter.emit(event, data.byteInputStream(), id)
            else -> emitter.emit(event, ctx.jsonMapper().toJsonString(data, data.javaClass).byteInputStream(), id)
        }
        if (emitter.closed) close()
    }

    private fun logTerminated() {
        log.log(Level.DEBUG, "Cannot send data, SseClient has been terminated.")
    }

    internal companion object {
        val log: System.Logger = System.getLogger("net.tegulis.kalin.sse")
    }
}

/** The handler behind `config.routes.sse(...)`. */
public class SseHandler
@JvmOverloads
constructor(private val heartbeat: Duration? = null, private val clientConsumer: Consumer<SseClient>) : Handler {
    override fun handle(ctx: Context) {
        if (ctx.header(Header.ACCEPT)?.contains("text/event-stream") != true) return
        val exchange = ctx.exchange()
        ctx.status(200)
        ctx.contentType("text/event-stream;charset=utf-8")
        ctx.header(Header.CACHE_CONTROL, "no-cache")
        ctx.header(Header.X_ACCEL_BUFFERING, "no")
        ctx.header(Header.CONNECTION, "close")
        val stream = exchange.startStreaming(-1)
        stream.flush()
        val client = SseClient(ctx, Emitter(stream), stream)
        exchange.onDisconnect { client.close() }
        heartbeat?.let { interval ->
            val ms = interval.inWholeMilliseconds
            client.heartbeat =
                scheduler.scheduleAtFixedRate(
                    { Thread.ofVirtual().start { if (!client.terminated()) client.sendComment("heartbeat") } },
                    ms,
                    ms,
                    TimeUnit.MILLISECONDS,
                )
        }
        var handedOff = false
        try {
            clientConsumer.accept(client)
            if (client.keepAlive && !client.terminated()) {
                // Release the handler thread; the request completes when the stream closes.
                ctx.future { client.closedFuture }
                handedOff = true
            }
        } finally {
            if (!handedOff) client.close()
        }
    }

    private companion object {
        val scheduler: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread.ofPlatform().daemon().name("kalin-sse-heartbeat").unstarted(r)
            }
        }
    }
}
