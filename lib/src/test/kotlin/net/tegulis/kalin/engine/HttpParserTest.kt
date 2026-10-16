package net.tegulis.kalin.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

class HttpParserTest :
    FunSpec({
        fun parse(raw: String, limits: Limits = Limits()): RequestHead {
            val bytes = raw.toByteArray(Charsets.ISO_8859_1)
            val parser = HttpParser(limits)
            val start = parser.skipLeadingEmptyLines(bytes, 0, bytes.size)
            val end = parser.findHeadEnd(bytes, start, bytes.size)
            end shouldNotBe -1
            return parser.parse(bytes, start, end)
        }

        fun status(raw: String, limits: Limits = Limits()): Int =
            shouldThrow<HttpParseException> {
                    val bytes = raw.toByteArray(Charsets.ISO_8859_1)
                    val parser = HttpParser(limits)
                    val start = parser.skipLeadingEmptyLines(bytes, 0, bytes.size)
                    val end = parser.findHeadEnd(bytes, start, bytes.size)
                    if (end < 0) throw HttpParseException(-1, "incomplete")
                    parser.parse(bytes, start, end)
                }
                .status

        test("parses a simple GET") {
            val head = parse("GET /hello?x=1&y=2 HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\n\r\n")
            head.method shouldBe "GET"
            head.path shouldBe "/hello"
            head.query shouldBe "x=1&y=2"
            head.target shouldBe "/hello?x=1&y=2"
            head.http11 shouldBe true
            head.keepAlive shouldBe true
            head.headers["host"] shouldBe "example.com"
            head.headers["ACCEPT"] shouldBe "*/*"
            head.hasBody shouldBe false
        }

        test("accepts bare LF line endings and leading empty lines") {
            val head = parse("\r\n\nPOST /x HTTP/1.1\nHost: a\nContent-Length: 3\n\n")
            head.method shouldBe "POST"
            head.contentLength shouldBe 3
        }

        test("trims optional whitespace around header values") {
            parse("GET / HTTP/1.1\r\nHost: a\r\nX-Test: \t value \t\r\n\r\n").headers["X-Test"] shouldBe "value"
        }

        test("HTTP/1.0 defaults to close, keep-alive opt-in") {
            parse("GET / HTTP/1.0\r\n\r\n").keepAlive shouldBe false
            parse("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n").keepAlive shouldBe true
            parse("GET / HTTP/1.1\r\nHost: a\r\nConnection: close\r\n\r\n").keepAlive shouldBe false
        }

        test("chunked and expect") {
            val head = parse("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\nExpect: 100-continue\r\n\r\n")
            head.chunked shouldBe true
            head.expectContinue shouldBe true
            head.contentLength shouldBe -1
        }

        test("absolute-form targets set the host") {
            val head = parse("GET http://example.org:8080/a/b?c HTTP/1.1\r\nHost: other\r\n\r\n")
            head.path shouldBe "/a/b"
            head.query shouldBe "c"
            head.host shouldBe "example.org:8080"
        }

        test("asterisk-form for OPTIONS") { parse("OPTIONS * HTTP/1.1\r\nHost: a\r\n\r\n").path shouldBe "*" }

        test("custom methods") { parse("PROPFIND /dav HTTP/1.1\r\nHost: a\r\n\r\n").method shouldBe "PROPFIND" }

        test("UTF-8 in target and header values") {
            val bytes = "GET /hä HTTP/1.1\r\nHost: a\r\nX-Name: Jürgen\r\n\r\n".toByteArray(Charsets.UTF_8)
            val parser = HttpParser(Limits())
            val head = parser.parse(bytes, 0, parser.findHeadEnd(bytes, 0, bytes.size))
            head.path shouldBe "/hä"
            head.headers["X-Name"] shouldBe "Jürgen"
        }

        test("rejects malformed request lines with 400") {
            status("GET  / HTTP/1.1\r\nHost: a\r\n\r\n") shouldBe 400
            status("GET /\r\nHost: a\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1 extra\r\nHost: a\r\n\r\n") shouldBe 400
            status("G(T / HTTP/1.1\r\nHost: a\r\n\r\n") shouldBe 400
            status("GET / HTTX/1.1\r\nHost: a\r\n\r\n") shouldBe 400
            status("GET foo HTTP/1.1\r\nHost: a\r\n\r\n") shouldBe 400
            status("GET /a\u0001b HTTP/1.1\r\nHost: a\r\n\r\n") shouldBe 400
        }

        test("rejects unsupported versions with 505") {
            status("GET / HTTP/2.0\r\nHost: a\r\n\r\n") shouldBe 505
            status("GET / HTTP/0.9\r\nHost: a\r\n\r\n") shouldBe 505
        }

        test("request smuggling checks") {
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: -1\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1,1\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: +5\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 99999999999999999999\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip\r\n\r\n") shouldBe 501
            status("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip, chunked\r\n\r\n") shouldBe 501
            status("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked, gzip\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n") shouldBe 400
            status("POST / HTTP/1.1\r\nHost: a\r\nContent-Length : 3\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1\r\nHost: a\r\n folded\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1\r\nHost: a\r\nX: a\rb\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1\r\nHost: a\r\nX: a\u0000b\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1\r\nHost: a\r\n: novalue\r\n\r\n") shouldBe 400
        }

        test("Host header rules") {
            status("GET / HTTP/1.1\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n") shouldBe 400
            status("GET / HTTP/1.1\r\nHost: a/b\r\n\r\n") shouldBe 400
            parse("GET / HTTP/1.0\r\n\r\n").host shouldBe null
        }

        test("unsupported expectations get 417") {
            status("GET / HTTP/1.1\r\nHost: a\r\nExpect: magic\r\n\r\n") shouldBe 417
        }

        test("limits: request line 414, headers 431") {
            val limits = Limits(maxRequestLineSize = 100, maxHeaderSize = 200, maxHeaderCount = 5)
            status("GET /" + "a".repeat(200) + " HTTP/1.1\r\nHost: a\r\n\r\n", limits) shouldBe 414
            status("GET / HTTP/1.1\r\nHost: a\r\nX: " + "b".repeat(300) + "\r\n\r\n", limits) shouldBe 431
            status("GET / HTTP/1.1\r\n" + (1..6).joinToString("") { "H$it: v\r\n" } + "\r\n", limits) shouldBe 431
        }

        test("incremental head detection across fragments") {
            val raw = "GET /inc HTTP/1.1\r\nHost: a\r\nX-A: 1\r\n\r\nNEXT".toByteArray()
            val parser = HttpParser(Limits())
            for (split in 1 until raw.size - 4) {
                parser.reset()
                parser.findHeadEnd(raw, 0, split) shouldBe -1
                parser.findHeadEnd(raw, 0, raw.size) shouldBe raw.size - 4
            }
        }

        test("fuzz: random input never crashes with anything but HttpParseException") {
            val random = Random(42)
            val alphabet =
                "GETPOST /?=&:\r\n\t HTTP/1.1Host:aContent-Length:0Transfer-Encoding:chunked\u0000ÿ"
                    .toByteArray(Charsets.ISO_8859_1)
            val seeds =
                listOf(
                        "GET / HTTP/1.1\r\nHost: a\r\n\r\n",
                        "POST /x HTTP/1.1\r\nHost: a\r\nContent-Length: 5\r\n\r\n",
                        "PUT /y?z HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n",
                    )
                    .map { it.toByteArray() }
            repeat(200_000) { i ->
                val bytes =
                    if (i % 2 == 0) ByteArray(random.nextInt(1, 120)) { alphabet[random.nextInt(alphabet.size)] }
                    else
                        seeds[i % seeds.size].copyOf().also { b ->
                            repeat(random.nextInt(1, 4)) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() }
                        }
                val parser = HttpParser(Limits(maxRequestLineSize = 64, maxHeaderSize = 128, maxHeaderCount = 8))
                try {
                    val start = parser.skipLeadingEmptyLines(bytes, 0, bytes.size)
                    val end = parser.findHeadEnd(bytes, start, bytes.size)
                    if (end >= 0) parser.parse(bytes, start, end)
                } catch (_: HttpParseException) {}
            }
        }
    })
