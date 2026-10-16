package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.KalinInternalApi
import net.tegulis.kalin.config.KalinConfig

/** Connection deadlines of the blocking session that clients must not be able to extend. */
@OptIn(KalinInternalApi::class)
class SessionTimeoutTest :
    FunSpec({
        fun withApp(configure: KalinConfig.() -> Unit, block: (Kalin) -> Unit) {
            val app = Kalin.create { config ->
                config.startup.showBanner = false
                config.server.transport = Transport.MEMORY
                config.routes.get("/") { it.result("root") }
                config.routes.post("/ignore") { it.result("ignored") }
                config.configure()
            }
            app.start()
            try {
                block(app)
            } finally {
                app.stop()
            }
        }

        fun millisSince(start: Long) = (System.nanoTime() - start) / 1_000_000

        test("empty lines between requests do not extend the idle timeout") {
            withApp({
                server.idleTimeout = 500.milliseconds
                server.requestTimeout = 10.seconds
            }) { app ->
                app.connectInMemory().use { conn ->
                    val start = System.nanoTime()
                    try {
                        while (millisSince(start) < 4000) {
                            conn.output.write("\r\n".toByteArray())
                            conn.output.flush()
                            Thread.sleep(50)
                        }
                    } catch (_: IOException) {}
                    millisSince(start) shouldBeLessThan 2500
                    conn.input.read() shouldBe -1
                }
            }
        }

        test("draining an unread request body has one overall deadline") {
            withApp({
                server.idleTimeout = 10.seconds
                server.requestTimeout = 500.milliseconds
            }) { app ->
                app.connectInMemory().use { conn ->
                    val start = System.nanoTime()
                    conn.output.write(
                        "POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n".toByteArray()
                    )
                    conn.output.flush()
                    val dribbler =
                        Thread.ofVirtual().start {
                            try {
                                while (millisSince(start) < 4000) {
                                    conn.output.write(ByteArray(10))
                                    conn.output.flush()
                                    Thread.sleep(50)
                                }
                            } catch (_: IOException) {} catch (_: InterruptedException) {}
                            conn.output.close()
                        }
                    val response = String(conn.input.readAllBytes())
                    response.endsWith("ignored") shouldBe true
                    millisSince(start) shouldBeLessThan 2500
                    dribbler.interrupt()
                }
            }
        }
    })
