package net.tegulis.kalin.async

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.tegulis.kalin.testtools.testKalin

class AsyncTimeoutTest :
    FunSpec({
        fun never() = CompletableFuture<Unit>().completeOnTimeout(Unit, 5, TimeUnit.SECONDS)

        test("timeout runs 500 error handlers once") {
            val calls = AtomicInteger()
            testKalin {
                app {
                    http.asyncTimeout = 50
                    routes.get("/") { ctx -> ctx.future { never() } }
                    routes.error(500) { ctx -> ctx.result("error handler ${calls.incrementAndGet()}") }
                }
                client.get("/").let {
                    it.code shouldBe 500
                    it.bodyAsText() shouldBe "error handler 1"
                }
                calls.get() shouldBe 1
            }
        }

        test("timeout replaces an earlier result") {
            testKalin {
                app {
                    http.asyncTimeout = 50
                    routes.get("/") { ctx ->
                        ctx.result("stale")
                        ctx.future { never() }
                    }
                }
                client.get("/").let {
                    it.code shouldBe 500
                    it.bodyAsText() shouldBe "Request timed out"
                }
            }
        }

        test("a throwing error handler after a timeout still produces a response") {
            testKalin {
                app {
                    http.asyncTimeout = 50
                    routes.get("/") { ctx -> ctx.future { never() } }
                    routes.error(500) { throw IllegalStateException("broken") }
                    routes.exception(IllegalStateException::class.java) { e, ctx ->
                        ctx.status(500).result("mapped " + e.message)
                    }
                }
                client.get("/").let {
                    it.code shouldBe 500
                    it.bodyAsText() shouldBe "mapped broken"
                }
            }
        }
    })
