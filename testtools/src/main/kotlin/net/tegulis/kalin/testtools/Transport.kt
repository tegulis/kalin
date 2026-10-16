package net.tegulis.kalin.testtools

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.json.JsonMapper

/** A raw bidirectional connection to the server under test. */
internal class RawConnection(val input: InputStream, val output: OutputStream, private val onClose: () -> Unit) :
    Closeable {
    override fun close() = onClose()
}

/** How the client reaches the server. */
internal interface ClientTransport : Closeable {
    val origin: String

    fun open(timeoutMillis: Long): RawConnection

    fun execute(method: String, path: String, request: TestRequest, mapper: () -> JsonMapper): TestResponse =
        executeRaw(this, method, path, request, mapper)

    override fun close() {}
}

/** Executes a request over a fresh raw connection. */
internal fun executeRaw(
    transport: ClientTransport,
    method: String,
    path: String,
    request: TestRequest,
    mapper: () -> JsonMapper,
): TestResponse {
    transport.open(request.timeout.inWholeMilliseconds).use { conn ->
        HttpWire.writeRequest(conn.output, request, method, request.target(path))
        val head = HttpWire.readHead(conn.input) ?: throw IOException("Server closed the connection without a response")
        val body = HttpWire.readBody(conn.input, head, method)
        return TestResponse(head.status, head.headers, body, mapper)
    }
}

/** In-memory transport (`TestMode.IN_MEMORY`). */
internal class MemoryClientTransport(private val app: Kalin) : ClientTransport {
    override val origin: String = "http://localhost"

    override fun open(timeoutMillis: Long): RawConnection {
        val conn = app.connectInMemory()
        val watchdog =
            if (timeoutMillis > 0)
                Thread.ofVirtual().start {
                    try {
                        Thread.sleep(timeoutMillis)
                        conn.close()
                    } catch (_: InterruptedException) {}
                }
            else null
        return RawConnection(conn.input, conn.output) {
            watchdog?.interrupt()
            conn.close()
        }
    }
}

/** Real network transport (`TestMode.REAL`); normal requests use the JDK `HttpClient`. */
internal class RealClientTransport(private val port: Int) : ClientTransport {
    override val origin: String = "http://127.0.0.1:$port"
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val client: HttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(executor)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    override fun open(timeoutMillis: Long): RawConnection {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
        socket.tcpNoDelay = true
        if (timeoutMillis > 0) socket.soTimeout = timeoutMillis.toInt()
        return RawConnection(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
    }

    override fun execute(method: String, path: String, request: TestRequest, mapper: () -> JsonMapper): TestResponse {
        val restricted = request.headers.any { it.first.lowercase() in RESTRICTED }
        val uri = runCatching { URI.create(origin + request.target(path)) }.getOrNull()
        // The JDK client refuses some headers and malformed URIs; send those requests over a raw socket
        if (restricted || uri == null) return executeRaw(this, method, path, request, mapper)
        val builder =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(request.timeout.inWholeMilliseconds))
                .method(
                    method,
                    request.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) }
                        ?: HttpRequest.BodyPublishers.noBody(),
                )
        for ((k, v) in request.headers) builder.header(k, v)
        if (request.cookies.isNotEmpty())
            builder.header("Cookie", request.cookies.joinToString("; ") { "${it.first}=${it.second}" })
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        val headers = ArrayList<Pair<String, String>>()
        response.headers().map().forEach { (k, values) -> if (k != ":status") values.forEach { headers.add(k to it) } }
        return TestResponse(response.statusCode(), headers, response.body() ?: ByteArray(0), mapper)
    }

    override fun close() {
        client.close()
        executor.close()
    }

    private companion object {
        val RESTRICTED = setOf("connection", "content-length", "expect", "host", "upgrade", "transfer-encoding")
    }
}

/** Reads responses from a raw connection until [count] were parsed or the server closed the connection. */
internal fun readRawResponses(
    conn: RawConnection,
    methods: List<String>,
    count: Int,
    mapper: () -> JsonMapper,
): RawResult {
    val raw = ByteArrayOutputStream()
    val responses = ArrayList<TestResponse>()
    var closed = false
    val tee =
        object : InputStream() {
            override fun read(): Int = conn.input.read().also { if (it >= 0) raw.write(it) }

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                conn.input.read(b, off, len).also { if (it > 0) raw.write(b, off, it) }
        }
    try {
        while (responses.size < count) {
            val head = HttpWire.readHead(tee)
            if (head == null) {
                closed = true
                break
            }
            val method = methods.getOrElse(responses.size) { "GET" }
            val body = HttpWire.readBody(tee, head, method)
            responses.add(TestResponse(head.status, head.headers, body, mapper))
            if (head.header("Connection")?.contains("close", true) == true) {
                closed = tee.read() < 0
                break
            }
        }
    } catch (_: IOException) {
        closed = true
    }
    return RawResult(raw.toString(Charsets.UTF_8), responses, closed)
}
