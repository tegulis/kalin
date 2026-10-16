package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.KalinInternalApi

/** The write timeout limits how long a write may stall, not how long a whole response may take. */
@OptIn(KalinInternalApi::class)
class WriteTimeoutTest :
    FunSpec({
        val file = Files.createTempFile("kalin-write-timeout", ".bin")
        Files.write(file, ByteArray(24 * 1024 * 1024) { (it % 251).toByte() })
        afterSpec { Files.deleteIfExists(file) }

        fun app(transport: Transport): Kalin = Kalin.create { config ->
            config.startup.showBanner = false
            config.server.engine = Engine.LOOM
            config.server.transport = transport
            config.server.port = 0
            config.server.host = "127.0.0.1"
            config.server.writeTimeout = 300.milliseconds
            config.routes.get("/bytes") { it.result(ByteArray(2 * 1024 * 1024)) }
            config.routes.get("/file") { it.exchange().sendFile(file, 0, Files.size(file)) }
        }

        /** Reads one response slowly; returns the declared and the received body length. */
        fun slowRead(input: InputStream, chunk: Int, pauseMs: Long): Pair<Long, Long> {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) return -1L to -2L
                head.append(b.toChar())
            }
            val length = Regex("(?i)Content-Length: (\\d+)").find(head)!!.groupValues[1].toLong()
            val buf = ByteArray(chunk)
            var received = 0L
            while (received < length) {
                val n = input.read(buf, 0, minOf(chunk.toLong(), length - received).toInt())
                if (n < 0) break
                received += n
                Thread.sleep(pauseMs)
            }
            return length to received
        }

        test("slow in-memory readers receive complete responses") {
            val app = app(Transport.MEMORY)
            app.start()
            try {
                for ((path, chunk) in listOf("/bytes" to 16 * 1024, "/file" to 256 * 1024)) {
                    app.connectInMemory().use { conn ->
                        conn.output.write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                        conn.output.flush()
                        val (length, received) = slowRead(conn.input, chunk, 5)
                        received shouldBe length
                    }
                }
            } finally {
                app.stop()
            }
        }

        test("slow socket readers receive complete files (LOOM)") {
            val app = app(Transport.NIO)
            app.start()
            try {
                Socket().use { socket ->
                    socket.receiveBufferSize = 64 * 1024
                    socket.connect(InetSocketAddress("127.0.0.1", app.port()))
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write("GET /file HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    val (length, received) = slowRead(socket.getInputStream(), 256 * 1024, 10)
                    received shouldBe length
                }
            } finally {
                app.stop()
            }
        }
    })
