package net.tegulis.kalin.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import net.tegulis.kalin.testtools.testKalin

class CorsVaryTest :
    FunSpec({
        test("Vary: Origin on every CORS response, without duplicates") {
            testKalin {
                app {
                    bundledPlugins.enableCors { cors -> cors.addRule { it.allowHost("example.com") } }
                    routes.get("/") { it.result("ok") }
                    routes.get("/vary") { it.header("Vary", "Accept-Language").result("ok") }
                }
                client.get("/").header("Vary") shouldBe "Origin"
                client.get("/") { header("Origin", "https://example.com") }.header("Vary") shouldBe "Origin"
                client.get("/") { header("Origin", "https://evil.com") }.header("Vary") shouldBe "Origin"
                client.get("/vary").header("Vary") shouldBe "Accept-Language"
            }
            testKalin {
                app {
                    bundledPlugins.enableCors { cors -> cors.addRule { it.anyHost() } }
                    routes.before { it.header("Vary", "Accept-Language") }
                    routes.get("/") { it.result("ok") }
                }
                client.get("/") { header("Origin", "https://example.com") }.header("Vary") shouldBe
                    "Accept-Language, Origin"
            }
        }

        test("warns about reflectClientOrigin with allowCredentials") {
            val messages = ArrayList<String>()
            val handler =
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        synchronized(messages) { messages.add(record.message) }
                    }

                    override fun flush() {}

                    override fun close() {}
                }
            val logger = Logger.getLogger("net.tegulis.kalin.CorsPlugin")
            logger.addHandler(handler)
            try {
                testKalin {
                    app {
                        bundledPlugins.enableCors { cors ->
                            cors.addRule {
                                it.reflectClientOrigin = true
                                it.allowCredentials = true
                            }
                        }
                        routes.get("/") { it.result("ok") }
                    }
                    client
                        .get("/") { header("Origin", "https://any.com") }
                        .header("Access-Control-Allow-Origin") shouldBe "https://any.com"
                }
            } finally {
                logger.removeHandler(handler)
            }
            messages.size shouldBe 1
            messages[0] shouldContain "any website can make credentialed requests"
        }
    })
