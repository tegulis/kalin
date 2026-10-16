package net.tegulis.kalin.testtools

import java.io.Closeable
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.Transport
import net.tegulis.kalin.json.JsonMapper

/** How tests reach the app. */
public enum class TestMode {
    /** An in-memory pipe instead of a socket; raw HTTP bytes still go through the real parser and writer. */
    IN_MEMORY,

    /** A real server on a random free port; the client is the JDK `HttpClient`. */
    REAL,
}

/**
 * Defaults for test runs, overridable with system properties so a whole suite can run against each engine and
 * transport: `kalin.testMode` (`IN_MEMORY`/`REAL`), `kalin.engine` and `kalin.transport`.
 */
public object TestDefaults {
    /** The default mode. */
    public val mode: TestMode =
        System.getProperty("kalin.testMode")?.let { TestMode.valueOf(it.uppercase()) } ?: TestMode.IN_MEMORY

    /** The default engine for REAL mode, or null to keep the app's setting. */
    public val engine: Engine? = System.getProperty("kalin.engine")?.let { Engine.valueOf(it.uppercase()) }

    /** The default transport for REAL mode, or null to keep the app's setting. */
    public val transport: Transport? = System.getProperty("kalin.transport")?.let { Transport.valueOf(it.uppercase()) }

    /** The default request timeout. */
    public var requestTimeout: Duration = 10.seconds
}

/** HTTP client for the app under test. */
public class TestClient internal constructor(private val start: () -> Pair<Kalin, ClientTransport>) : Closeable {
    private var started: Pair<Kalin, ClientTransport>? = null

    /** Headers sent with every request. */
    public val defaultHeaders: MutableMap<String, String> = LinkedHashMap()

    private fun transport(): ClientTransport = (started ?: start().also { started = it }).second

    private val mapper: () -> JsonMapper = { (started ?: start().also { started = it }).first.jsonMapper() }

    /** The base URL, for example `http://127.0.0.1:41234` (or `http://localhost` in memory). */
    public val origin: String
        get() = transport().origin

    private fun newRequest(block: TestRequest.() -> Unit): TestRequest =
        TestRequest(mapper).apply {
            timeout = TestDefaults.requestTimeout
            defaultHeaders.forEach { (k, v) -> header(k, v) }
            block()
        }

    /** Sends a request with any [method]. */
    public fun request(method: String, path: String, block: TestRequest.() -> Unit = {}): TestResponse =
        transport().execute(method, path, newRequest(block), mapper)

    /** GET [path]. */
    public fun get(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("GET", path, block)

    /** POST [path]. */
    public fun post(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("POST", path, block)

    /** PUT [path]. */
    public fun put(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("PUT", path, block)

    /** PATCH [path]. */
    public fun patch(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("PATCH", path, block)

    /** DELETE [path]. */
    public fun delete(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("DELETE", path, block)

    /** HEAD [path]. */
    public fun head(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("HEAD", path, block)

    /** OPTIONS [path]. */
    public fun options(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("OPTIONS", path, block)

    /** QUERY [path]. */
    public fun query(path: String, block: TestRequest.() -> Unit = {}): TestResponse = request("QUERY", path, block)

    /**
     * Sends raw bytes (one or more pipelined requests) and parses up to [responses] responses. For protocol edge cases:
     * `client.raw("GET / HTTP/1.1\r\nHost: x\r\n\r\n")`.
     */
    public fun raw(request: String, responses: Int = 1, timeout: Duration = TestDefaults.requestTimeout): RawResult =
        raw(request.toByteArray(Charsets.ISO_8859_1), responses, timeout)

    /** Sends raw bytes and parses up to [responses] responses. */
    public fun raw(request: ByteArray, responses: Int = 1, timeout: Duration = TestDefaults.requestTimeout): RawResult {
        val methods =
            Regex("(?m)^([A-Z]+) \\S+ HTTP/")
                .findAll(String(request, Charsets.ISO_8859_1))
                .map { it.groupValues[1] }
                .toList()
        transport().open(timeout.inWholeMilliseconds).use { conn ->
            try {
                conn.output.write(request)
                conn.output.flush()
            } catch (_: IOException) {}
            return readRawResponses(conn, methods, responses, mapper)
        }
    }

    /** Opens an SSE stream on [path]; leaving [block] disconnects. */
    public fun <R> sse(path: String, request: TestRequest.() -> Unit = {}, block: SseSession.() -> R): R {
        val req = newRequest {
            header("Accept", "text/event-stream")
            request()
        }
        val conn = transport().open(0)
        try {
            HttpWire.writeRequest(conn.output, req, "GET", req.target(path))
            val head = HttpWire.readHead(conn.input) ?: throw IOException("Server closed the connection")
            val body = HttpWire.bodyStream(conn.input, head, "GET")
            return SseSession(conn, head.status, head.headers, body).use { it.block() }
        } finally {
            conn.close()
        }
    }

    override fun close() {
        started?.second?.close()
    }
}

/** Information about the server under test. */
public class TestServer internal constructor(private val scope: TestKalinScope) {
    /** The app. */
    public val app: Kalin
        get() = scope.started().first

    /** The bound port (0 in memory). */
    public val port: Int
        get() = app.port().coerceAtLeast(0)

    /** The base URL. */
    public val url: String
        get() = scope.client.origin
}

/** The receiver of [testKalin]. */
public class TestKalinScope
internal constructor(
    /** The test mode. */
    public val mode: TestMode,
    private val engine: Engine?,
    private val transport: Transport?,
) : Closeable {
    private val modules = ArrayList<KalinConfig.() -> Unit>()
    private var custom: Kalin? = null
    private var running: Pair<Kalin, ClientTransport>? = null

    /** The client. Its first request starts the app. */
    public val client: TestClient = TestClient { started() }

    /** The server (starts the app on first access). */
    public val server: TestServer = TestServer(this)

    /** Adds configuration, like the block of `Kalin.create { }` (receiver = config). */
    public fun app(configure: KalinConfig.() -> Unit) {
        check(running == null) { "app { } must be called before the first request" }
        modules.add(configure)
    }

    /** Uses an already created app instead of [app] blocks. */
    public fun app(kalin: Kalin) {
        check(running == null) { "app(...) must be called before the first request" }
        custom = kalin
    }

    internal fun started(): Pair<Kalin, ClientTransport> {
        running?.let {
            return it
        }
        val kalin =
            custom
                ?: Kalin.create { config ->
                    config.startup.showBanner = false
                    modules.forEach { config.it() }
                }
        val result = startForTest(kalin, mode, engine, transport)
        running = result
        return result
    }

    override fun close() {
        client.close()
        running?.first?.stop()
    }
}

internal fun startForTest(
    kalin: Kalin,
    mode: TestMode,
    engine: Engine?,
    transport: Transport?,
): Pair<Kalin, ClientTransport> {
    val server = kalin.unsafeConfig().server
    when (mode) {
        TestMode.IN_MEMORY -> server.transport = Transport.MEMORY
        TestMode.REAL -> {
            server.port = 0
            server.host = "127.0.0.1"
            engine?.let { server.engine = it }
            transport?.let { server.transport = it }
            if (server.transport == Transport.MEMORY) server.transport = Transport.AUTO
        }
    }
    kalin.start()
    val clientTransport =
        if (mode == TestMode.IN_MEMORY) MemoryClientTransport(kalin) else RealClientTransport(kalin.port())
    return kalin to clientTransport
}

/**
 * Starts an app, runs [block] and stops the app (Ktor `testApplication` style). The [TestClient] is also available as a
 * context parameter, so test helpers can declare `context(client: TestClient)`.
 *
 * ```
 * testKalin {
 *     app { routes.get("/ping") { ctx -> ctx.result("PONG") } }
 *     client.get("/ping").bodyAsText() shouldBe "PONG"
 * }
 * ```
 */
public fun testKalin(
    mode: TestMode = TestDefaults.mode,
    engine: Engine? = TestDefaults.engine,
    transport: Transport? = TestDefaults.transport,
    block: context(TestClient) TestKalinScope.() -> Unit,
) {
    TestKalinScope(mode, engine, transport).use { scope -> context(scope.client) { scope.block() } }
}

/** Javalin-style test tool: `KalinTest.test(app) { server, client -> }`. */
public object KalinTest {
    /** A test case receiving the started app and a client. */
    public fun interface TestCase {
        /** Runs the test. */
        @Throws(Exception::class) public fun accept(server: Kalin, client: TestClient)
    }

    /** Starts [app], runs [testCase] and stops the app. */
    @JvmStatic
    @JvmOverloads
    public fun test(app: Kalin = Kalin.create(), mode: TestMode = TestDefaults.mode, testCase: TestCase) {
        val (kalin, transport) = startForTest(app, mode, TestDefaults.engine, TestDefaults.transport)
        val client = TestClient { kalin to transport }
        try {
            testCase.accept(kalin, client)
        } finally {
            client.close()
            kalin.stop()
        }
    }
}
