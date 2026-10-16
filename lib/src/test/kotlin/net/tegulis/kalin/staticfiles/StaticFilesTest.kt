package net.tegulis.kalin.staticfiles

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.testtools.testKalin

class StaticFilesTest :
    FunSpec({
        val root = tempdir()
        File(root, "index.html").writeText("<h1>external index</h1>")
        File(root, "hello.txt").writeText("hello static")
        File(root, "big.js").writeText("console.log('x');\n".repeat(1000))
        File(root, "data.bin").writeBytes(ByteArray(10_000) { it.toByte() })
        File(root, "dir").mkdirs()
        File(root, "dir/nested.json").writeText("""{"nested":true}""")
        File(root, "noindex").mkdirs()
        File(root, "noindex/a.txt").writeText("a")
        File(root, ".hidden").writeText("secret")
        File(root, "pre.css").writeText("x".repeat(5000))
        File(root, "pre.css.gz")
            .writeBytes(
                java.io
                    .ByteArrayOutputStream()
                    .also { java.util.zip.GZIPOutputStream(it).use { g -> g.write("PRECOMPRESSED".toByteArray()) } }
                    .toByteArray()
            )
        val outside = tempdir()
        File(outside, "secret.txt").writeText("outside")
        try {
            Files.createSymbolicLink(File(root, "link.txt").toPath(), File(outside, "secret.txt").toPath())
        } catch (_: Exception) {}

        test("external files with mime types, cache headers and index files") {
            testKalin {
                app { staticFiles.add(root.absolutePath, Location.EXTERNAL) }
                client.get("/hello.txt").let {
                    it.code shouldBe 200
                    it.bodyAsText() shouldBe "hello static"
                    it.header("Content-Type") shouldBe "text/plain;charset=utf-8"
                    it.header("Cache-Control") shouldBe "max-age=0"
                    it.header("Accept-Ranges") shouldBe "bytes"
                    it.header("ETag") shouldContain "\""
                    it.header("Last-Modified") shouldContain "GMT"
                }
                client.get("/").bodyAsText() shouldBe "<h1>external index</h1>"
                client.get("/dir/nested.json").contentType()!!.mimeType shouldBe ContentType.JSON
                client.get("/data.bin").bodyAsBytes().size shouldBe 10_000
                client.get("/dir").let {
                    it.code shouldBe 301
                    it.header("Location") shouldBe "/dir/"
                }
                client.get("/noindex/").code shouldBe 404
                client.get("/missing.txt").code shouldBe 404
                client.head("/hello.txt").let {
                    it.code shouldBe 200
                    it.header("Content-Length") shouldBe "12"
                    it.bodyAsBytes().size shouldBe 0
                }
                client.post("/hello.txt").code shouldBe 404
            }
        }

        test("path traversal and symlinks outside the root are rejected") {
            testKalin {
                app { staticFiles.add(root.absolutePath, Location.EXTERNAL) }
                client.raw("GET /../${outside.name}/secret.txt HTTP/1.1\r\nHost: x\r\n\r\n").response.code shouldBe 404
                client.raw("GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n").response.code shouldBe 404
                client.raw("GET /dir/..%2f..%2fetc%2fpasswd HTTP/1.1\r\nHost: x\r\n\r\n").response.code shouldBe 404
                client.raw("GET /hello.txt%00.html HTTP/1.1\r\nHost: x\r\n\r\n").response.code shouldBe 404
                client.get("/link.txt").code shouldBe 404
            }
        }

        test("hidden files and directory listing options") {
            testKalin {
                app {
                    staticFiles.add {
                        it.directory = root.absolutePath
                        it.location = Location.EXTERNAL
                        it.serveHiddenFiles = false
                        it.directoryListing = true
                    }
                }
                client.get("/.hidden").code shouldBe 404
                client.get("/noindex/").let {
                    it.code shouldBe 200
                    it.bodyAsText() shouldContain "Index of /noindex/"
                    it.bodyAsText() shouldContain "href=\"a.txt\""
                    it.bodyAsText() shouldNotContain ".hidden"
                }
            }
        }

        test("paths that are invalid on the file system are not found") {
            resolveBelow(root.toPath(), "/a\u0000b") shouldBe null
            resolveBelow(root.toPath(), "/../x") shouldBe null
            resolveBelow(root.toPath(), "/dir/nested.json") shouldBe root.toPath().resolve("dir/nested.json")
            testKalin {
                app { staticFiles.add(root.absolutePath, Location.EXTERNAL) }
                client.get("/a%3Ab%3C%7C%2A%3F").code shouldBe 404
            }
        }

        test("Windows 8.3 short names cannot reach hidden files").config(
            enabledIf = { System.getProperty("os.name").startsWith("Windows") }
        ) {
            val dir = tempdir()
            File(dir, ".htaccess").writeText("secret")
            testKalin {
                app { staticFiles.add(dir.absolutePath, Location.EXTERNAL) }
                client.get("/HTACCE~1").code shouldBe 404
                client.get("/a%3Ab").code shouldBe 404
            }
        }

        test("conditional requests and ranges") {
            testKalin {
                app { staticFiles.add(root.absolutePath, Location.EXTERNAL) }
                val first = client.get("/data.bin")
                val etag = first.header("ETag")!!
                client
                    .get("/data.bin") { header("If-None-Match", etag) }
                    .let {
                        it.code shouldBe 304
                        it.bodyAsBytes().size shouldBe 0
                    }
                client.get("/data.bin") { header("If-Modified-Since", first.header("Last-Modified")!!) }.code shouldBe
                    304
                client
                    .get("/data.bin") { header("Range", "bytes=0-9") }
                    .let {
                        it.code shouldBe 206
                        it.header("Content-Range") shouldBe "bytes 0-9/10000"
                        it.bodyAsBytes().toList() shouldBe (0 until 10).map { b -> b.toByte() }
                    }
                client.get("/data.bin") { header("Range", "bytes=9995-") }.bodyAsBytes().size shouldBe 5
                client
                    .get("/data.bin") { header("Range", "bytes=20000-") }
                    .let {
                        it.code shouldBe 416
                        it.header("Content-Range") shouldBe "bytes */10000"
                    }
                client
                    .get("/data.bin") {
                        header("Range", "bytes=0-9")
                        header("If-Range", "\"old\"")
                    }
                    .code shouldBe 200
            }
        }

        test("range requests follow RFC 9110") {
            testKalin {
                app { staticFiles.add(root.absolutePath, Location.EXTERNAL) }
                fun range(value: String, head: Boolean = false) =
                    if (head) client.head("/data.bin") { header("Range", value) }
                    else client.get("/data.bin") { header("Range", value) }
                // Syntactically invalid: ignored
                for (invalid in
                    listOf("bytes=5-3", "bytes=abc", "bytes=1-x", "bytes=+1-2", "bytes=", "items=0-1", "0-1")) {
                    range(invalid).let {
                        it.code shouldBe 200
                        it.header("Content-Range") shouldBe null
                        it.bodyAsBytes().size shouldBe 10_000
                    }
                }
                range("bytes=0-1, 5-3").code shouldBe 200
                // Satisfiable
                range("BYTES=5-5").let {
                    it.code shouldBe 206
                    it.header("Content-Range") shouldBe "bytes 5-5/10000"
                }
                range("bytes=9990-99999").header("Content-Range") shouldBe "bytes 9990-9999/10000"
                range("bytes=9990-99999999999999999999999").header("Content-Range") shouldBe "bytes 9990-9999/10000"
                range("bytes=-20000").let {
                    it.code shouldBe 206
                    it.header("Content-Range") shouldBe "bytes 0-9999/10000"
                    it.bodyAsBytes().size shouldBe 10_000
                }
                range("bytes=-3").header("Content-Range") shouldBe "bytes 9997-9999/10000"
                range("bytes=20000-, 2-3").header("Content-Range") shouldBe "bytes 2-3/10000"
                // Unsatisfiable
                for (unsatisfiable in listOf("bytes=10000-", "bytes=10000-10005", "bytes=-0", "bytes=20000-,-0")) {
                    range(unsatisfiable).let {
                        it.code shouldBe 416
                        it.header("Content-Range") shouldBe "bytes */10000"
                    }
                }
                // Several satisfiable ranges: the full resource
                range("bytes=0-1,5-6").let {
                    it.code shouldBe 200
                    it.bodyAsBytes().size shouldBe 10_000
                }
                // Not for HEAD
                range("bytes=0-1", head = true).let {
                    it.code shouldBe 200
                    it.header("Content-Range") shouldBe null
                }
            }
        }

        test("compression of static files and precompressed variants") {
            testKalin {
                app {
                    staticFiles.add {
                        it.directory = root.absolutePath
                        it.location = Location.EXTERNAL
                        it.precompressedFiles = true
                    }
                }
                client
                    .get("/big.js") { header("Accept-Encoding", "gzip") }
                    .let {
                        it.header("Content-Encoding") shouldBe "gzip"
                        String(GZIPInputStream(ByteArrayInputStream(it.bodyAsBytes())).readAllBytes()) shouldBe
                            "console.log('x');\n".repeat(1000)
                    }
                client.get("/big.js").header("Content-Encoding") shouldBe null
                client
                    .get("/pre.css") { header("Accept-Encoding", "gzip") }
                    .let {
                        it.header("Content-Encoding") shouldBe "gzip"
                        String(GZIPInputStream(ByteArrayInputStream(it.bodyAsBytes())).readAllBytes()) shouldBe
                            "PRECOMPRESSED"
                    }
            }
        }

        test("ETags differ per content coding and validate across codings") {
            testKalin {
                app {
                    staticFiles.add {
                        it.directory = root.absolutePath
                        it.location = Location.EXTERNAL
                        it.precompressedFiles = true
                    }
                }
                for (path in listOf("/big.js", "/pre.css")) {
                    val identity = client.get(path)
                    val gzip = client.get(path) { header("Accept-Encoding", "gzip") }
                    gzip.header("Content-Encoding") shouldBe "gzip"
                    val tag = identity.header("ETag")!!
                    gzip.header("ETag") shouldBe tag.removeSuffix("\"") + "-gzip\""
                    identity.header("Vary") shouldBe "Accept-Encoding"
                    client
                        .get(path) {
                            header("Accept-Encoding", "gzip")
                            header("If-None-Match", tag)
                        }
                        .let {
                            it.code shouldBe 304
                            it.header("ETag") shouldBe gzip.header("ETag")
                            it.header("Vary") shouldBe "Accept-Encoding"
                        }
                    client.get(path) { header("If-None-Match", "W/" + gzip.header("ETag")) }.code shouldBe 304
                    client.get(path) { header("If-None-Match", "\"other\"") }.code shouldBe 200
                    // Ranges are sent uncompressed, so only the identity tag satisfies If-Range
                    client
                        .get(path) {
                            header("Accept-Encoding", "gzip")
                            header("Range", "bytes=0-1")
                            header("If-Range", gzip.header("ETag")!!)
                        }
                        .let {
                            it.code shouldBe 200
                            it.header("Content-Encoding") shouldBe "gzip"
                        }
                    client
                        .get(path) {
                            header("Accept-Encoding", "gzip")
                            header("Range", "bytes=0-1")
                            header("If-Range", tag)
                        }
                        .let {
                            it.code shouldBe 206
                            it.header("ETag") shouldBe tag
                            it.header("Content-Encoding") shouldBe null
                        }
                }
                client.get("/hello.txt").header("Vary") shouldBe null
            }
        }

        test("classpath files, hosted path and custom headers") {
            testKalin {
                app {
                    staticFiles.add("/public", Location.CLASSPATH)
                    staticFiles.add {
                        it.hostedPath = "/assets"
                        it.directory = "public"
                        it.headers = mapOf("Cache-Control" to "max-age=3600")
                    }
                    routes.get("/styles.css") { it.result("endpoint wins") }
                }
                client.get("/").bodyAsText() shouldContain "classpath index"
                client.get("/sub/file.txt").bodyAsText() shouldBe "sub file\n"
                client.get("/styles.css").bodyAsText() shouldBe "endpoint wins"
                client.get("/assets/styles.css").let {
                    it.bodyAsText() shouldBe "body { color: red; }\n"
                    it.header("Cache-Control") shouldBe "max-age=3600"
                    it.contentType()!!.mimeType shouldBe "text/css"
                }
                client.get("/assets/nope.css").code shouldBe 404
            }
        }

        test("before handlers run for static files, roles and skip function") {
            testKalin {
                app {
                    staticFiles.add {
                        it.directory = root.absolutePath
                        it.location = Location.EXTERNAL
                        it.skipFileFunction = { ctx -> ctx.path().endsWith(".bin") }
                        it.roles = setOf(object : net.tegulis.kalin.security.RouteRole {})
                    }
                    routes.before { it.header("X-Before", "yes") }
                    routes.beforeMatched { it.header("X-Roles", it.routeRoles().size.toString()) }
                }
                client.get("/hello.txt").let {
                    it.header("X-Before") shouldBe "yes"
                    it.header("X-Roles") shouldBe "1"
                }
                client.get("/data.bin").code shouldBe 404
            }
        }

        test("single page application roots") {
            testKalin {
                app {
                    spaRoot.addFile("/", "/spa/index.html")
                    spaRoot.addHandler("/admin") { it.html("admin spa") }
                    routes.get("/api/data") { it.result("data") }
                }
                client.get("/some/route") { header("Accept", "text/html") }.bodyAsText() shouldBe "<html>spa</html>\n"
                client.get("/admin/users") { header("Accept", "text/html") }.bodyAsText() shouldBe "admin spa"
                client.get("/api/data") { header("Accept", "text/html") }.bodyAsText() shouldBe "data"
                client.get("/some/route") { header("Accept", "application/json") }.code shouldBe 404
            }
        }
    })
