package net.tegulis.kalin.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import net.tegulis.kalin.config.Key
import net.tegulis.kalin.testtools.MultipartFile
import net.tegulis.kalin.testtools.testKalin

class ContextTest :
    FunSpec({
        test("query params") {
            testKalin {
                app {
                    routes.get("/q") { ctx ->
                        ctx.result(
                            "${ctx.queryParam("a")}|${ctx.queryParams("b")}|${ctx.queryParam("missing")}|${ctx.queryString()}|${ctx.queryParam("sp")}"
                        )
                    }
                }
                client.get("/q?a=1&b=2&b=3&sp=x+y%20z").bodyAsText() shouldBe
                    "1|[2, 3]|null|a=1&b=2&b=3&sp=x+y%20z|x y z"
                client.get("/q?a=%C3%A6%C3%B8%C3%A5").bodyAsText() shouldStartWith "æøå|"
            }
        }

        test("form params, body and body bytes") {
            testKalin {
                app {
                    routes.post("/form") { ctx ->
                        ctx.result("${ctx.formParam("a")}|${ctx.formParams("b")}|${ctx.formParamMap().keys}")
                    }
                    routes.post("/body") { ctx ->
                        ctx.result(
                            ctx.body() +
                                "|" +
                                ctx.bodyAsBytes().size +
                                "|" +
                                ctx.contentLength() +
                                "|" +
                                ctx.contentType()
                        )
                    }
                }
                client.post("/form") { form("a" to "1", "b" to "x y", "b" to "æ") }.bodyAsText() shouldBe
                    "1|[x y, æ]|[a, b]"
                client.post("/body") { body("hello") }.bodyAsText() shouldBe "hello|5|5|text/plain;charset=utf-8"
            }
        }

        test("strict content types") {
            testKalin {
                app {
                    http.strictContentTypes = true
                    routes.post("/form") { ctx -> ctx.result("${ctx.formParam("a")}") }
                }
                client.post("/form") { body("a=1") }.bodyAsText() shouldBe "null"
                client.post("/form") { form("a" to "1") }.bodyAsText() shouldBe "1"
            }
        }

        test("max request size gives 413") {
            testKalin {
                app {
                    http.maxRequestSize = 10
                    routes.post("/") { ctx -> ctx.result(ctx.body()) }
                    routes.post("/stream") { ctx -> ctx.result(ctx.bodyInputStream().readAllBytes().size.toString()) }
                }
                client.post("/") { body("0123456789") }.bodyAsText() shouldBe "0123456789"
                client.post("/") { body("0123456789x") }.code shouldBe 413
                client.post("/stream") { body("0123456789x") }.bodyAsText() shouldBe "11"
                client
                    .raw(
                        "POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nb\r\n0123456789x\r\n0\r\n\r\n"
                    )
                    .response
                    .code shouldBe 413
            }
        }

        test("headers, header map, user agent, protocol, method override") {
            testKalin {
                app {
                    routes.get("/h") { ctx ->
                        ctx.result(
                            "${ctx.header("X-A")}|${ctx.header("x-a")}|${ctx.headerMap()["X-A"]}|${ctx.userAgent()}|${ctx.protocol()}|${ctx.method()}"
                        )
                    }
                    routes.delete("/h") { ctx -> ctx.result("deleted") }
                }
                client
                    .get("/h") {
                        header("X-A", "1")
                        header("User-Agent", "test-agent")
                    }
                    .bodyAsText() shouldBe "1|1|1|test-agent|HTTP/1.1|GET"
                client.post("/h") { header("X-HTTP-Method-Override", "DELETE") }.bodyAsText() shouldBe "deleted"
            }
        }

        test("url, host, scheme, ip, port, forwarded headers") {
            testKalin {
                app {
                    routes.get("/u") { ctx ->
                        ctx.result("${ctx.scheme()}|${ctx.host()}|${ctx.url()}|${ctx.fullUrl()}|${ctx.ip()}")
                    }
                }
                client.get("/u?x=1") { header("Host", "example.com:8080") }.bodyAsText() shouldBe
                    "http|example.com:8080|http://example.com:8080/u|http://example.com:8080/u?x=1|127.0.0.1"
            }
            testKalin {
                app {
                    contextResolver.useForwardedHeaders(trustedProxies = listOf("10.0.0.2"))
                    routes.get("/u") { ctx -> ctx.result("${ctx.scheme()}|${ctx.host()}|${ctx.ip()}") }
                }
                client
                    .get("/u") {
                        header("X-Forwarded-For", "10.0.0.1, 10.0.0.2")
                        header("X-Forwarded-Proto", "https")
                        header("X-Forwarded-Host", "public.example")
                    }
                    .bodyAsText() shouldBe "https|public.example|10.0.0.1"
                client
                    .get("/u") { header("Forwarded", "for=\"[2001:db8::1]:4711\";proto=https;host=f.example") }
                    .bodyAsText() shouldBe "https|f.example|2001:db8::1"
            }
        }

        test("cookies in and out") {
            testKalin {
                app {
                    routes.get("/read") { ctx -> ctx.result("${ctx.cookie("a")}|${ctx.cookieMap()}") }
                    routes.get("/write") { ctx ->
                        ctx.cookie("x", "1")
                        ctx.cookie("x", "2")
                        ctx.cookie(
                            Cookie(
                                "y",
                                "z",
                                path = "/p",
                                maxAge = 60,
                                secure = true,
                                isHttpOnly = true,
                                sameSite = SameSite.STRICT,
                            )
                        )
                        ctx.removeCookie("old")
                    }
                }
                client
                    .get("/read") {
                        cookie("a", "1")
                        cookie("b", "2")
                    }
                    .bodyAsText() shouldBe "1|{a=1, b=2}"
                val r = client.get("/write")
                val setCookies = r.headers["set-cookie"]!!
                setCookies.size shouldBe 3
                setCookies[0] shouldBe "x=2; Path=/"
                setCookies[1] shouldStartWith "y=z; Path=/p; Max-Age=60; Expires="
                setCookies[1] shouldContain "; Secure; HttpOnly; SameSite=Strict"
                setCookies[2] shouldStartWith "old=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970"
            }
        }

        test("cookie store round trips through the cookie") {
            testKalin {
                app {
                    routes.post("/store") { ctx ->
                        ctx.cookieStore()["string"] = "Hello world!"
                        ctx.cookieStore()["i"] = 42
                        ctx.cookieStore()["list"] = listOf("One", "Two", "Three")
                    }
                    routes.get("/read") { ctx ->
                        val i: Int = ctx.cookieStore()["i"]
                        val list: List<String> = ctx.cookieStore()["list"]
                        ctx.result("${ctx.cookieStore().get<String>("string")} $i $list")
                    }
                }
                val cookie = client.post("/store").cookies["kalin-cookie-store"]!!
                client.get("/read") { cookie("kalin-cookie-store", cookie) }.bodyAsText() shouldBe
                    "Hello world! 42 [One, Two, Three]"
            }
        }

        test("attributes and app data") {
            val key = Key<String>("my-key")
            testKalin {
                app {
                    appData(key, "app-value")
                    routes.before { it.attribute("a", "from-before") }
                    routes.get("/") { ctx ->
                        val computed = ctx.attributeOrCompute("c") { "computed" }
                        ctx.result(
                            "${ctx.attribute<String>("a")}|$computed|${ctx.attributeMap().keys.sorted()}|${ctx.appData(key)}"
                        )
                    }
                }
                client.get("/").bodyAsText() shouldBe "from-before|computed|[a, c]|app-value"
            }
        }

        test("basic auth credentials") {
            testKalin {
                app {
                    routes.get("/") { ctx ->
                        ctx.result(ctx.basicAuthCredentials()?.let { "${it.username}:${it.password}" } ?: "none")
                    }
                }
                val token = Base64.getEncoder().encodeToString("user:pa:ss".toByteArray())
                client.get("/") { header("Authorization", "Basic $token") }.bodyAsText() shouldBe "user:pa:ss"
                client.get("/") { header("Authorization", "Basic !!!") }.bodyAsText() shouldBe "none"
                client.get("/").bodyAsText() shouldBe "none"
            }
        }

        test("result variants and result()") {
            testKalin {
                app {
                    routes.get("/string") { it.result("string") }
                    routes.get("/bytes") { it.result(byteArrayOf(1, 2, 3)) }
                    routes.get("/stream") { it.result(ByteArrayInputStream("stream".toByteArray())) }
                    routes.get("/unknown-stream") {
                        it.result(
                            java.io.SequenceInputStream(
                                ByteArrayInputStream("a".toByteArray()),
                                ByteArrayInputStream("b".toByteArray()),
                            )
                        )
                    }
                    routes.get("/result") { ctx ->
                        ctx.result("first")
                        ctx.result(ctx.result() + " second")
                    }
                    routes.get("/html") { it.html("<h1>x</h1>") }
                    routes.get("/json") { it.json(mapOf("a" to listOf(1, 2))) }
                    routes.get("/status") { it.status(HttpStatus.IM_A_TEAPOT).result("tea") }
                    routes.get("/nocontent") { it.status(204) }
                }
                client.get("/string").bodyAsText() shouldBe "string"
                client.get("/bytes").bodyAsBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
                client.get("/stream").bodyAsText() shouldBe "stream"
                client.get("/unknown-stream").bodyAsText() shouldBe "ab"
                client.get("/result").bodyAsText() shouldBe "first second"
                client.get("/html").let {
                    it.contentType()!!.mimeType shouldBe "text/html"
                    it.bodyAsText() shouldBe "<h1>x</h1>"
                }
                client.get("/json").let {
                    it.contentType()!!.mimeType shouldBe "application/json"
                    it.bodyAsText() shouldBe """{"a":[1,2]}"""
                }
                client.get("/status").code shouldBe 418
                client.get("/nocontent").let {
                    it.code shouldBe 204
                    it.header("Content-Length") shouldBe null
                    it.header("Content-Type") shouldBe null
                }
            }
        }

        test("output stream: small is fixed length, large is chunked") {
            testKalin {
                app {
                    routes.get("/small") { it.outputStream().write("small".toByteArray()) }
                    routes.get("/large") { ctx ->
                        repeat(10) { ctx.outputStream().write(ByteArray(10_000) { 'a'.code.toByte() }) }
                    }
                    routes.get("/flush") { ctx ->
                        ctx.outputStream().write("a".toByteArray())
                        ctx.outputStream().flush()
                        ctx.outputStream().write("b".toByteArray())
                    }
                }
                client.raw("GET /small HTTP/1.1\r\nHost: x\r\n\r\n").response.header("Content-Length") shouldBe "5"
                client.raw("GET /large HTTP/1.1\r\nHost: x\r\nAccept-Encoding: identity\r\n\r\n").response.let {
                    it.header("Transfer-Encoding") shouldBe "chunked"
                    it.bodyAsBytes().size shouldBe 100_000
                }
                client.get("/flush").bodyAsText() shouldBe "ab"
            }
        }

        test("redirects") {
            testKalin {
                app {
                    routes.get("/r") { it.redirect("/target") }
                    routes.get("/r301") { it.redirect("/target", HttpStatus.MOVED_PERMANENTLY) }
                    routes.get("/thrown") { throw RedirectResponse(HttpStatus.SEE_OTHER) }
                }
                client.get("/r").let {
                    it.code shouldBe 302
                    it.header("Location") shouldBe "/target"
                }
                client.get("/r301").code shouldBe 301
                client.get("/thrown").code shouldBe 303
            }
        }

        test("response headers") {
            testKalin {
                app {
                    routes.get("/") { ctx ->
                        ctx.header("X-A", "1")
                            .addHeader("X-B", "1")
                            .addHeader("X-B", "2")
                            .header("X-C", "c")
                            .removeHeader("X-C")
                        ctx.result("${ctx.responseHeader("X-A")} ${ctx.responseHeaders("X-B")}")
                    }
                }
                client.get("/").let {
                    it.headers["x-b"] shouldBe listOf("1", "2")
                    it.header("X-C") shouldBe null
                    it.bodyAsText() shouldBe "1 [1, 2]"
                    it.header("Date")!!.endsWith("GMT") shouldBe true
                }
            }
        }

        test("gzip and deflate compression") {
            val big = "compress me ".repeat(500)
            testKalin {
                app {
                    routes.get("/big") { it.result(big) }
                    routes.get("/small") { it.result("tiny") }
                    routes.get("/image") { it.contentType("image/png").result(big) }
                    routes.get("/stream") {
                        it.result(
                            java.io.SequenceInputStream(
                                ByteArrayInputStream(big.toByteArray()),
                                ByteArrayInputStream(big.toByteArray()),
                            )
                        )
                    }
                    routes.get("/disabled") { it.disableCompression().result(big) }
                }
                client
                    .get("/big") { header("Accept-Encoding", "gzip, deflate") }
                    .let {
                        it.header("Content-Encoding") shouldBe "gzip"
                        it.header("Vary") shouldBe "Accept-Encoding"
                        String(GZIPInputStream(ByteArrayInputStream(it.bodyAsBytes())).readAllBytes()) shouldBe big
                    }
                client
                    .get("/big") { header("Accept-Encoding", "gzip;q=0, deflate") }
                    .header("Content-Encoding") shouldBe null
                client.get("/big").header("Content-Encoding") shouldBe null
                client.get("/small") { header("Accept-Encoding", "gzip") }.header("Content-Encoding") shouldBe null
                client.get("/image") { header("Accept-Encoding", "gzip") }.header("Content-Encoding") shouldBe null
                client.get("/disabled") { header("Accept-Encoding", "gzip") }.header("Content-Encoding") shouldBe null
                client
                    .get("/stream") { header("Accept-Encoding", "gzip") }
                    .let {
                        it.header("Content-Encoding") shouldBe "gzip"
                        String(GZIPInputStream(ByteArrayInputStream(it.bodyAsBytes())).readAllBytes()) shouldBe
                            big + big
                    }
            }
            testKalin {
                app {
                    http.compressionStrategy = net.tegulis.kalin.compression.CompressionStrategy.GZIP_AND_DEFLATE
                    routes.get("/big") { it.result(big) }
                }
                client
                    .get("/big") { header("Accept-Encoding", "deflate") }
                    .let {
                        it.header("Content-Encoding") shouldBe "deflate"
                        String(
                            java.util.zip.InflaterInputStream(ByteArrayInputStream(it.bodyAsBytes())).readAllBytes()
                        ) shouldBe big
                    }
            }
        }

        test("etags for dynamic responses") {
            testKalin {
                app {
                    http.generateEtags = true
                    routes.get("/") { it.result("etagged") }
                    routes.get("/custom") { it.header("ETag", "\"v1\"").result("custom") }
                    routes.post("/") { it.result("no etag for POST") }
                }
                val etag = client.get("/").header("ETag")!!
                client
                    .get("/") { header("If-None-Match", etag) }
                    .let {
                        it.code shouldBe 304
                        it.bodyAsBytes().size shouldBe 0
                    }
                client.get("/custom") { header("If-None-Match", "\"v1\"") }.code shouldBe 304
                client.post("/").header("ETag") shouldBe null
            }
        }

        test("writeSeekableStream supports ranges") {
            val data = (0 until 1000).joinToString("") { (it % 10).toString() }
            testKalin {
                app {
                    routes.get("/video") {
                        it.writeSeekableStream(
                            ByteArrayInputStream(data.toByteArray()),
                            "video/mp4",
                            data.length.toLong(),
                        )
                    }
                }
                client.get("/video").let {
                    it.code shouldBe 200
                    it.header("Accept-Ranges") shouldBe "bytes"
                    it.bodyAsText() shouldBe data
                }
                client
                    .get("/video") { header("Range", "bytes=10-19") }
                    .let {
                        it.code shouldBe 206
                        it.header("Content-Range") shouldBe "bytes 10-19/1000"
                        it.bodyAsText() shouldBe "0123456789"
                    }
                client.get("/video") { header("Range", "bytes=-5") }.bodyAsText() shouldBe "56789"
                client.get("/video") { header("Range", "bytes=2000-") }.code shouldBe 416
            }
        }

        test("multipart uploads") {
            testKalin {
                app {
                    http.multipartConfig.maxInMemoryFileSize = 10
                    routes.post("/upload") { ctx ->
                        val files = ctx.uploadedFiles("files")
                        ctx.result(
                            "${ctx.isMultipartFormData()}|${ctx.formParam("field")}|" +
                                files.joinToString {
                                    "${it.filename()}:${it.size()}:${it.contentType()}:${String(it.contentAsBytes())}"
                                } +
                                "|${ctx.uploadedFile("missing")}|${ctx.uploadedFileMap().keys}"
                        )
                    }
                }
                client
                    .post("/upload") {
                        multipart(
                            fields = mapOf("field" to "value æ"),
                            files =
                                listOf(
                                    MultipartFile("files", "a.txt", "small".toByteArray(), "text/plain"),
                                    MultipartFile("files", "b.bin", "this is larger than ten bytes".toByteArray()),
                                ),
                        )
                    }
                    .bodyAsText() shouldBe
                    "true|value æ|a.txt:5:text/plain:small, b.bin:29:application/octet-stream:this is larger than ten bytes|null|[files]"
            }
        }

        test("context plugins and ctx.with") {
            class Ext(val ctx: Context) {
                fun hello() = "hello " + ctx.queryParam("name")
            }
            class MyPlugin : net.tegulis.kalin.plugin.ContextPlugin<Unit, Ext>() {
                override fun createExtension(context: Context) = Ext(context)
            }
            testKalin {
                app {
                    registerPlugin(MyPlugin())
                    routes.get("/") { it.result(it.with(MyPlugin::class).hello()) }
                }
                client.get("/?name=kalin").bodyAsText() shouldBe "hello kalin"
            }
        }

        test("render with a custom FileRenderer") {
            testKalin {
                app {
                    fileRenderer { path, model, _ -> "rendered $path with ${model["x"]}" }
                    routes.get("/") { it.render("/template.txt", mapOf("x" to 1)) }
                }
                client.get("/").let {
                    it.bodyAsText() shouldBe "rendered /template.txt with 1"
                    it.contentType()!!.mimeType shouldBe "text/html"
                }
            }
        }
    })

class SecurityTest :
    FunSpec({
        test("cookie values that could inject attributes are rejected") {
            testKalin {
                app {
                    routes.get("/bad") { it.cookie("a", "1; Domain=evil.com") }
                    routes.get("/good") { it.cookie("a", java.net.URLEncoder.encode("1; x", Charsets.UTF_8)) }
                }
                client.get("/bad").let {
                    it.code shouldBe 500
                    it.header("Set-Cookie") shouldBe null
                }
                client.get("/good").header("Set-Cookie") shouldStartWith "a=1%3B+x"
            }
        }

        test("method override only applies to POST") {
            testKalin {
                app {
                    routes.get("/r") { it.result("get") }
                    routes.delete("/r") { it.result("delete") }
                }
                client.get("/r") { header("X-HTTP-Method-Override", "DELETE") }.bodyAsText() shouldBe "get"
                client.post("/r") { header("X-HTTP-Method-Override", "delete") }.bodyAsText() shouldBe "delete"
            }
        }

        test("method override can be disabled") {
            testKalin {
                app {
                    http.methodOverride = false
                    routes.post("/r") { it.result("post:${it.method()}") }
                    routes.delete("/r") { it.result("delete") }
                }
                client.post("/r") { header("X-HTTP-Method-Override", "DELETE") }.bodyAsText() shouldBe "post:POST"
            }
        }
    })
