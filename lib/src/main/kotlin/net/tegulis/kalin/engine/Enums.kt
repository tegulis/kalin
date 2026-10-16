package net.tegulis.kalin.engine

/** The request processing engine. */
public enum class Engine {
    /**
     * One virtual thread per connection with blocking I/O. Simple, robust, works on every platform. Supports
     * [Transport.NIO] (and the in-memory test transport) only.
     */
    LOOM,

    /**
     * N event loops with non-blocking connection state machines. Handlers run on virtual threads by default (see
     * [HandlerExecution]). Supports [Transport.NIO] and [Transport.EPOLL].
     */
    EVENT_LOOP,
}

/** The network transport. */
public enum class Transport {
    /** [EPOLL] on Linux when native access is available and the engine supports it, else [NIO]. */
    AUTO,

    /** The JDK NIO socket channels (all platforms). */
    NIO,

    /** Native Linux epoll through the FFM API (event loop engine only). */
    EPOLL,

    /** In-memory pipes instead of sockets, used by `kalin-testtools`. */
    MEMORY,
}

/** Where `Engine.EVENT_LOOP` runs request handlers. */
public enum class HandlerExecution {
    /** On a new virtual thread; blocking code is safe. */
    VIRTUAL_THREAD,

    /** Directly on the event loop thread. Fastest, but handlers must never block. */
    EVENT_LOOP,
}

/** What to do with connections above `maxConnections`. */
public enum class OverloadPolicy {
    /** Close the socket at once. */
    CLOSE,

    /** Answer with `503 Service Unavailable` and close. */
    REJECT_503,
}
