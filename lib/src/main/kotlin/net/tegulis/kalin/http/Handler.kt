package net.tegulis.kalin.http

/** The main request handler interface. Set the response through [Context] methods. */
public fun interface Handler {
    /** Handles the request. Any thrown exception is passed to the exception mappers. */
    @Throws(Exception::class) public fun handle(ctx: Context)
}

/**
 * Marker for handlers that never block. In `Engine.EVENT_LOOP` such endpoint handlers run directly on the event loop
 * thread (when no blocking filters apply to the request), which saves a thread hand-off.
 */
public fun interface NonBlockingHandler : Handler

/** Creates a [NonBlockingHandler] from a lambda. */
public fun nonBlocking(handler: (Context) -> Unit): NonBlockingHandler = NonBlockingHandler { handler(it) }

/** Handles exceptions of type [T] thrown by handlers. */
public fun interface ExceptionHandler<T : Exception> {
    /** Handles [exception] for the request [ctx]. */
    public fun handle(exception: T, ctx: Context)
}

/** Called after the response has been written, with the execution time in milliseconds. */
public fun interface RequestLogger {
    /** Logs the finished request. */
    @Throws(Exception::class) public fun handle(ctx: Context, executionTimeMs: Float)
}
