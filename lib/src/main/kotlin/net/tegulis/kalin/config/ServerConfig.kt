package net.tegulis.kalin.config

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.HandlerExecution
import net.tegulis.kalin.engine.OverloadPolicy
import net.tegulis.kalin.engine.Transport

/** Configuration of Kalin's own HTTP/1.1 server (replaces Javalin's `config.jetty`). */
public class ServerConfig {
    /** The host or IP to bind to; null binds all interfaces. */
    public var host: String? = null

    /** The port to bind to; 0 picks a random free port. */
    public var port: Int = 8080

    /** The listen backlog. */
    public var backlog: Int = 1024

    /** The processing engine. */
    public var engine: Engine = Engine.LOOM

    /** The network transport. `AUTO` picks EPOLL for the event loop engine on Linux, else NIO. */
    public var transport: Transport = Transport.AUTO

    /** The number of event loops for [Engine.EVENT_LOOP]. */
    public var eventLoops: Int = Runtime.getRuntime().availableProcessors()

    /** Where the event loop engine runs handlers. */
    public var handlerExecution: HandlerExecution = HandlerExecution.VIRTUAL_THREAD

    /** The maximum number of open connections; 0 means unlimited. */
    public var maxConnections: Int = 0

    /** What to do with connections above [maxConnections]. */
    public var overloadPolicy: OverloadPolicy = OverloadPolicy.CLOSE

    /** How long an idle keep-alive connection is kept open. */
    public var idleTimeout: Duration = 30.seconds

    /** The time allowed to receive a complete request head after its first byte, and between body reads. */
    public var requestTimeout: Duration = 30.seconds

    /** The time allowed for a blocked response write to make progress. */
    public var writeTimeout: Duration = 30.seconds

    /** How long `stop()` waits for in-flight requests before closing their connections. */
    public var shutdownTimeout: Duration = 10.seconds

    /** The maximum length of the request line. Longer lines get `414 URI Too Long`. */
    public var maxRequestLineSize: Int = 8 * 1024

    /** The maximum size of the header section. Larger heads get `431`. */
    public var maxHeaderSize: Int = 16 * 1024

    /** The maximum number of header fields. */
    public var maxHeaderCount: Int = 100

    /**
     * The maximum number of unread request body bytes that are read and discarded to keep a connection alive after the
     * response. Larger unread bodies close the connection.
     */
    public var drainLimit: Long = 256 * 1024

    /** The value of the `Server` response header; null sends none. */
    public var serverHeader: String? = null

    /** Sets `TCP_NODELAY` on accepted sockets. */
    public var tcpNoDelay: Boolean = true

    /** The size of the per-connection response buffer. */
    public var outputBufferSize: Int = 16 * 1024
}
