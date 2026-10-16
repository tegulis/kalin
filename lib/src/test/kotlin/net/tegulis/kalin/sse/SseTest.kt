package net.tegulis.kalin.sse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.testtools.SseEvent
import net.tegulis.kalin.testtools.testKalin

class SseTest :
    FunSpec({
        test("events, data, ids, comments and retry") {
            testKalin {
                app {
                    routes.sse("/sse") { client ->
                        client.sendEvent("hi", "there", "1")
                        client.sendData("plain")
                        client.sendEvent(mapOf("a" to 1))
                        client.sendComment("just a comment")
                        client.sendRetry(1500)
                        client.sendEvent("multi", "line1\nline2")
                    }
                }
                client.sse("/sse") {
                    status shouldBe 200
                    header("Content-Type") shouldBe "text/event-stream;charset=utf-8"
                    header("Cache-Control") shouldBe "no-cache"
                    take(4) shouldBe
                        listOf(
                            SseEvent("hi", "there", "1"),
                            SseEvent(null, "plain", "1"),
                            SseEvent("message", """{"a":1}""", "1"),
                            SseEvent("multi", "line1\nline2", "1"),
                        )
                    takeComment() shouldBe "just a comment"
                    retry shouldBe 1500
                    awaitEnd() shouldBe true
                }
            }
        }

        test("raw SSE framing is chunked") {
            testKalin {
                app { routes.sse("/sse") { it.sendEvent("e", "d") } }
                val raw = client.raw("GET /sse HTTP/1.1\r\nHost: x\r\nAccept: text/event-stream\r\n\r\n")
                raw.text shouldContain "Transfer-Encoding: chunked"
                raw.response.bodyAsText() shouldBe "event: e\ndata: d\n\n"
            }
        }

        test("comments cannot inject fields through any line terminator") {
            val out = java.io.ByteArrayOutputStream()
            Emitter(out).emit("a\rdata: evil\r\nb\nc")
            out.toString(Charsets.UTF_8) shouldBe ": a\n: data: evil\n: b\n: c\n\n"
        }

        test("keepAlive clients can be used from other threads and closed") {
            val clients = ConcurrentLinkedQueue<SseClient>()
            val closed = CountDownLatch(1)
            testKalin {
                app {
                    routes.sse("/sse") { client ->
                        client.keepAlive()
                        client.onClose { closed.countDown() }
                        clients.add(client)
                    }
                }
                client.sse("/sse") {
                    val deadline = System.currentTimeMillis() + 5000
                    while (clients.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
                    clients.forEach { it.sendEvent("broadcast", "hello") }
                    take(1).single().data shouldBe "hello"
                    clients.forEach { it.close() }
                    awaitEnd() shouldBe true
                }
                closed.await(5, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("client disconnect fires onClose quickly") {
            val closed = CountDownLatch(1)
            val started = CountDownLatch(1)
            testKalin {
                app {
                    routes.sse("/sse") { client ->
                        client.keepAlive()
                        client.onClose { closed.countDown() }
                        client.sendEvent("ready", "1")
                        started.countDown()
                    }
                }
                client.sse("/sse") { take(1).size shouldBe 1 }
                closed.await(5, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("blocking handler loop notices disconnect") {
            val terminated = CountDownLatch(1)
            testKalin {
                app {
                    routes.sse("/sse") { client ->
                        while (!client.terminated()) {
                            client.sendEvent("tick", "t")
                            Thread.sleep(10)
                        }
                        terminated.countDown()
                    }
                }
                client.sse("/sse") { take(3).size shouldBe 3 }
                terminated.await(5, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("heartbeat comments") {
            testKalin {
                app {
                    sse.heartbeat = 50.milliseconds
                    routes.sse("/sse") { it.keepAlive() }
                }
                client.sse("/sse") { takeComment(5.seconds) shouldBe "heartbeat" }
            }
        }

        test("last event id and non-SSE requests") {
            testKalin {
                app { routes.sse("/sse") { it.sendData(it.lastEventId() ?: "none") } }
                client.sse("/sse", request = { header("Last-Event-ID", "42") }) { take(1).single().data shouldBe "42" }
                client.get("/sse").let {
                    it.code shouldBe 200
                    it.bodyAsText() shouldBe ""
                }
            }
        }
    })
