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
    })
