package net.tegulis.kalin.compression

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.InflaterInputStream

class CompressionTest :
    FunSpec({
        val strategy = CompressionStrategy.GZIP_AND_DEFLATE

        test("deflate round-trips and frees its deflater on close") {
            val bos = ByteArrayOutputStream()
            val stream = strategy.compress(CompressionType.DEFLATE, bos) as EndingDeflaterOutputStream
            stream.use { it.write("hello hello hello".toByteArray()) }
            InflaterInputStream(bos.toByteArray().inputStream()).readAllBytes().decodeToString() shouldBe
                "hello hello hello"
            shouldThrowAny { stream.deflater.bytesRead }
            stream.close()
        }

        test("deflate frees its deflater when closing fails") {
            val failing =
                object : OutputStream() {
                    override fun write(b: Int) = throw IOException("gone")
                }
            val stream = strategy.compress(CompressionType.DEFLATE, failing) as EndingDeflaterOutputStream
            shouldThrow<IOException> { stream.close() }
            shouldThrowAny { stream.deflater.bytesRead }
        }
    })
