package net.tegulis.kalin.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import net.tegulis.kalin.testtools.MultipartFile
import net.tegulis.kalin.testtools.testKalin

private fun Path.fileCount(): Int = Files.list(this).use { it.count().toInt() }

private fun waitUntilEmpty(dir: Path) {
    val deadline = System.currentTimeMillis() + 5000
    while (dir.fileCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
}

class MultipartTest :
    FunSpec({
        test("spilled uploads are deleted after the response") {
            val dir = Files.createTempDirectory("kalin-multipart-test")
            try {
                testKalin {
                    app {
                        http.multipartConfig.maxInMemoryFileSize = 10
                        http.multipartConfig.cacheDirectory = dir.toString()
                        routes.post("/upload") { ctx ->
                            val file = ctx.uploadedFile("file")!!
                            ctx.result("${dir.fileCount()}|${String(file.contentAsBytes())}")
                        }
                    }
                    client
                        .post("/upload") {
                            multipart(
                                files = listOf(MultipartFile("file", "a.bin", "larger than ten bytes".toByteArray()))
                            )
                        }
                        .bodyAsText() shouldBe "1|larger than ten bytes"
                    waitUntilEmpty(dir)
                    dir.fileCount() shouldBe 0
                }
                testKalin {
                    app {
                        http.multipartConfig.maxInMemoryFileSize = 10
                        http.multipartConfig.maxFileSize = 15
                        http.multipartConfig.cacheDirectory = dir.toString()
                        routes.post("/upload") { ctx -> ctx.result("${ctx.uploadedFile("file")}") }
                    }
                    client
                        .post("/upload") {
                            multipart(
                                files = listOf(MultipartFile("file", "a.bin", "larger than fifteen".toByteArray()))
                            )
                        }
                        .code shouldBe 413
                    dir.fileCount() shouldBe 0
                }
            } finally {
                Files.deleteIfExists(dir)
            }
        }

        test("malformed filename* is a bad request") {
            testKalin {
                app { routes.post("/upload") { ctx -> ctx.result("${ctx.uploadedFile("file")?.filename()}") } }
                fun upload(filename: String) =
                    client.post("/upload") {
                        body(
                            ("--b\r\nContent-Disposition: form-data; name=\"file\"; filename*=$filename\r\n\r\n" +
                                    "x\r\n--b--\r\n")
                                .toByteArray(),
                            "multipart/form-data; boundary=b",
                        )
                    }
                upload("UTF-8''%C3%A6.txt").bodyAsText() shouldBe "æ.txt"
                upload("UTF-8''%ZZ.txt").code shouldBe 400
            }
        }

        test("maxTotalRequestSize falls back to http.maxRequestSize") {
            testKalin {
                app {
                    http.maxRequestSize = 400
                    routes.post("/upload") { ctx -> ctx.result("${ctx.uploadedFile("file")?.size()}") }
                }
                client
                    .post("/upload") { multipart(files = listOf(MultipartFile("file", "a.bin", ByteArray(500)))) }
                    .code shouldBe 413
                client
                    .post("/upload") { multipart(files = listOf(MultipartFile("file", "a.bin", ByteArray(10)))) }
                    .bodyAsText() shouldBe "10"
            }
            testKalin {
                app {
                    http.maxRequestSize = 400
                    http.multipartConfig.maxTotalRequestSize = 1000
                    routes.post("/upload") { ctx -> ctx.result("${ctx.uploadedFile("file")?.size()}") }
                }
                client
                    .post("/upload") { multipart(files = listOf(MultipartFile("file", "a.bin", ByteArray(500)))) }
                    .bodyAsText() shouldBe "500"
            }
        }

        test("maxParts limits the number of parts") {
            testKalin {
                app {
                    http.multipartConfig.maxParts(2)
                    routes.post("/upload") { ctx -> ctx.result("${ctx.formParamMap().size}") }
                }
                client.post("/upload") { multipart(fields = mapOf("a" to "1", "b" to "2")) }.bodyAsText() shouldBe "2"
                client.post("/upload") { multipart(fields = mapOf("a" to "1", "b" to "2", "c" to "3")) }.code shouldBe
                    413
            }
        }

        test("files spill to disk once the in-memory budget is used") {
            val dir = Files.createTempDirectory("kalin-multipart-test")
            try {
                testKalin {
                    app {
                        http.multipartConfig.maxInMemoryFileSize = 10
                        http.multipartConfig.cacheDirectory = dir.toString()
                        routes.post("/upload") { ctx ->
                            val sizes = ctx.uploadedFiles("f").map { it.size() }
                            ctx.result("${dir.fileCount()}|$sizes")
                        }
                    }
                    client
                        .post("/upload") {
                            multipart(files = (1..5).map { MultipartFile("f", "$it.bin", ByteArray(10)) })
                        }
                        .bodyAsText() shouldBe "1|[10, 10, 10, 10, 10]"
                    waitUntilEmpty(dir)
                }
            } finally {
                Files.deleteIfExists(dir)
            }
        }
    })
