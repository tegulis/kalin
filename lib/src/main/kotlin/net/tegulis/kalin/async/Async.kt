package net.tegulis.kalin.async

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Handler

/** Configuration of one `ctx.async { }` task. */
public class AsyncTaskConfig {
    /** The executor; null uses a virtual thread per task. */
    @JvmField public var executor: ExecutorService? = null

    /** Timeout in milliseconds; 0 disables it. */
    @JvmField public var timeout: Long = 0

    internal var onTimeout: Consumer<Context>? = null

    /** Called (as part of the request lifecycle) when the task times out. */
    public fun onTimeout(listener: Consumer<Context>) {
        onTimeout = listener
    }
}

/** Shared executors. */
internal object KalinExecutors {
    val virtual: ExecutorService =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kalin-async-", 0).factory())
}

/** Dispatches coroutine continuations to virtual threads. */
public object VirtualThreadDispatcher : ContinuationInterceptor {
    override val key: CoroutineContext.Key<*> = ContinuationInterceptor

    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        object : Continuation<T> {
            override val context: CoroutineContext = continuation.context

            override fun resumeWith(result: Result<T>) {
                if (Thread.currentThread().isVirtual) continuation.resumeWith(result)
                else KalinExecutors.virtual.execute { continuation.resumeWith(result) }
            }
        }
}

/** A handler written as a suspend function. */
public fun interface SuspendHandler {
    /** Handles the request; may suspend. */
    public suspend fun handle(ctx: Context)
}

/**
 * Wraps a suspend function as a [Handler]. The coroutine runs on virtual threads (stdlib coroutines only; no
 * kotlinx.coroutines needed) and the request continues when it completes.
 *
 * ```
 * routes.get("/x", suspendHandler { ctx -> ctx.result(client.fetch().await()) })
 * ```
 */
public fun suspendHandler(block: suspend (Context) -> Unit): Handler = Handler { ctx ->
    ctx.future {
        val future = CompletableFuture<Unit>()
        val body: suspend () -> Unit = { block(ctx) }
        body.startCoroutine(
            object : Continuation<Unit> {
                override val context: CoroutineContext = EmptyCoroutineContext + VirtualThreadDispatcher

                override fun resumeWith(result: Result<Unit>) {
                    val error = result.exceptionOrNull()
                    if (error == null) future.complete(Unit) else future.completeExceptionally(error)
                }
            }
        )
        future
    }
}

/** Wraps a [SuspendHandler] as a [Handler]. */
public fun SuspendHandler.toHandler(): Handler = suspendHandler { handle(it) }

/** Suspends until this future completes. */
public suspend fun <T> CompletableFuture<T>.await(): T {
    if (isDone) {
        try {
            @Suppress("BlockingMethodInNonBlockingContext")
            return join()
        } catch (e: java.util.concurrent.CompletionException) {
            throw e.cause ?: e
        }
    }
    return suspendCoroutine { cont ->
        whenComplete { value, error ->
            if (error == null) cont.resume(value)
            else cont.resumeWithException((error as? java.util.concurrent.CompletionException)?.cause ?: error)
        }
    }
}
