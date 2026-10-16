package net.tegulis.kalin.http

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.tegulis.kalin.config.ContextResolverConfig
import net.tegulis.kalin.config.ForwardedIp
import net.tegulis.kalin.testtools.testKalin

class ForwardedHeadersTest :
    FunSpec({
        // The server's own host differs per test mode (localhost in memory, 127.0.0.1:port over sockets)
        val show: (Context) -> Unit = { ctx ->
            val host = ctx.host()?.substringBefore(':')?.let { if (it == "127.0.0.1") "localhost" else it }
            ctx.result("${ctx.ip()}|${ctx.scheme()}|$host|${ctx.forwardedFor()}")
        }

        test("rightmost untrusted entry is the client by default") {
            testKalin {
                app {
                    contextResolver.useForwardedHeaders()
                    routes.get("/") { show(it) }
                }
                client.get("/") { header("X-Forwarded-For", "6.6.6.6, 1.2.3.4") }.bodyAsText() shouldBe
                    "1.2.3.4|http|localhost|[6.6.6.6, 1.2.3.4]"
                client.get("/").bodyAsText() shouldBe "127.0.0.1|http|localhost|[]"
            }
        }

        test("trusted proxies are skipped, IPv4 and IPv6 CIDRs") {
            testKalin {
                app {
                    contextResolver.useForwardedHeaders(
                        ForwardedIp.RIGHTMOST,
                        listOf("10.0.0.0/8", "fd00::/8", "9.9.9.9"),
                    )
                    routes.get("/") { show(it) }
                }
                client
                    .get("/") {
                        header("X-Forwarded-For", "6.6.6.6, 1.2.3.4, 10.1.2.3, 9.9.9.9")
                        header("X-Forwarded-Proto", "http, https, https, https")
                        header("X-Forwarded-Host", "evil, good, x, y")
                    }
                    .bodyAsText() shouldBe "1.2.3.4|https|good|[6.6.6.6, 1.2.3.4, 10.1.2.3, 9.9.9.9]"
                client
                    .get("/") {
                        header("X-Forwarded-For", "1.2.3.4, 10.1.2.3")
                        header("X-Forwarded-Proto", "https")
                    }
                    .bodyAsText() shouldBe "1.2.3.4|https|localhost|[1.2.3.4, 10.1.2.3]"
                client
                    .get("/") {
                        header("Forwarded", "for=6.6.6.6;proto=http, for=\"[2001:db8::1]:4711\";proto=https;host=a")
                        header("Forwarded", "for=\"[fd00::5]\";proto=http;host=b")
                    }
                    .bodyAsText() shouldBe "2001:db8::1|https|a|[6.6.6.6, 2001:db8::1, fd00::5]"
                client.get("/") { header("X-Forwarded-For", "10.0.0.1, 10.0.0.2") }.bodyAsText() shouldBe
                    "10.0.0.1|http|localhost|[10.0.0.1, 10.0.0.2]"
                client.get("/") { header("X-Real-IP", "5.5.5.5") }.bodyAsText() shouldBe
                    "5.5.5.5|http|localhost|[5.5.5.5]"
            }
        }

        test("leftmost mode takes the first entry") {
            testKalin {
                app {
                    contextResolver.useForwardedHeaders(ForwardedIp.LEFTMOST)
                    routes.get("/") { show(it) }
                }
                client
                    .get("/") {
                        header("X-Forwarded-For", "6.6.6.6, 1.2.3.4")
                        header("X-Forwarded-Proto", "https")
                    }
                    .bodyAsText() shouldBe "6.6.6.6|https|localhost|[6.6.6.6, 1.2.3.4]"
            }
        }

        test("forwardedFor without useForwardedHeaders") {
            testKalin {
                app { routes.get("/") { show(it) } }
                client.get("/") { header("X-Forwarded-For", "6.6.6.6:80, [2001:db8::2]:8080") }.bodyAsText() shouldBe
                    "127.0.0.1|http|localhost|[6.6.6.6, 2001:db8::2]"
            }
        }

        test("trusted proxies must be IP literals") {
            for (bad in
                listOf("proxy.example.com", "localhost", "10.0.0.0/33", "fd00::/129", "1.2.3.256", "10.0.0.0/x")) {
                shouldThrow<IllegalArgumentException> {
                    ContextResolverConfig().useForwardedHeaders(trustedProxies = listOf(bad))
                }
            }
            ContextResolverConfig().useForwardedHeaders(trustedProxies = listOf("::1", "[::1]", "0.0.0.0/0", "::/0"))
        }
    })
