package net.tegulis.kalin.async

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import net.tegulis.kalin.testtools.testKalin

class AsyncTest :
    FunSpec({
        test("future results run the rest of the lifecycle") {
            testKalin {
                app {
                    routes.get("/future") { ctx ->
                        ctx.future { CompletableFuture.supplyAsync { "async" }.thenAccept { ctx.result(it) } }
                    }
                    routes.get("/failing") { ctx ->
                        ctx.future { CompletableFuture.failedFuture<Unit>(IllegalStateException("boom")) }
                    }
                    routes.exception(IllegalStateException::class.java) { e, ctx ->
                        ctx.status(503).result("mapped " + e.message)
                    }
                    routes.after { it.header("X-After", "ran") }
                }
                client.get("/future").let {
                    it.bodyAsText() shouldBe "async"
                    it.header("X-After") shouldBe "ran"
                }
                client.get("/failing").let {
                    it.code shouldBe 503
                    it.bodyAsText() shouldBe "mapped boom"
                }
            }
        }

        test("async tasks with timeout") {
            testKalin {
                app {
                    routes.get("/async") { ctx ->
                        ctx.async {
                            ctx.result("done on " + (if (Thread.currentThread().isVirtual) "virtual" else "platform"))
                        }
                    }
                    routes.get("/slow") { ctx ->
                        ctx.async({ cfg ->
                            cfg.timeout = 50
                            cfg.onTimeout { it.status(504).result("Request took too long") }
                        }) {
                            Thread.sleep(2000)
                            ctx.result("too late")
                        }
                    }
                }
                client.get("/async").bodyAsText() shouldBe "done on virtual"
                client.get("/slow").let {
                    it.code shouldBe 504
                    it.bodyAsText() shouldBe "Request took too long"
                }
            }
        }

        test("global async timeout") {
            testKalin {
                app {
                    http.asyncTimeout = 50
                    routes.get("/") { ctx ->
                        ctx.future { CompletableFuture<Unit>().completeOnTimeout(Unit, 5, TimeUnit.SECONDS) }
                    }
                }
                client.get("/").let {
                    it.code shouldBe 500
                    it.bodyAsText() shouldBe "Request timed out"
                }
            }
        }

        test("suspend handlers with await") {
            testKalin {
                app {
                    routes.get(
                        "/suspend",
                        suspendHandler { ctx ->
                            val a = CompletableFuture.supplyAsync { 20 }.await()
                            val b = CompletableFuture.supplyAsync { 22 }.await()
                            ctx.result("${a + b} ${Thread.currentThread().isVirtual}")
                        },
                    )
                    routes.get(
                        "/suspend-fail",
                        suspendHandler {
                            CompletableFuture.failedFuture<Int>(IllegalArgumentException("nope")).await()
                        },
                    )
                    routes.exception(IllegalArgumentException::class.java) { e, ctx ->
                        ctx.status(400).result(e.message!!)
                    }
                }
                client.get("/suspend").bodyAsText() shouldBe "42 true"
                client.get("/suspend-fail").let {
                    it.code shouldBe 400
                    it.bodyAsText() shouldBe "nope"
                }
            }
        }

        test("handlers run on virtual threads") {
            testKalin {
                app { routes.get("/") { it.result(Thread.currentThread().isVirtual.toString()) } }
                client.get("/").bodyAsText() shouldBe "true"
            }
        }
    })
