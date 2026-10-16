package net.tegulis.kalin.engine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class ChunkedDecoderTest :
    FunSpec({
        fun decodeAll(input: ByteArray, step: Int = input.size, maxData: Int = Int.MAX_VALUE): Pair<String, Int> {
            val decoder = ChunkedDecoder(Limits())
            val out = ByteArrayOutputStream()
            var pos = 0
            while (pos < input.size && !decoder.done) {
                pos +=
                    decoder.decode(input, pos, minOf(step, input.size - pos), maxData) { b, o, l -> out.write(b, o, l) }
            }
            decoder.done shouldBe true
            return out.toString() to pos
        }

        test("decodes chunks, extensions and trailers") {
            val body =
                "4\r\nWiki\r\n5;ext=1\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\nX-Trailer: t\r\n\r\nNEXT"
                    .toByteArray()
            val (text, consumed) = decodeAll(body)
            text shouldBe "Wikipedia in\r\n\r\nchunks."
            consumed shouldBe body.size - 4
        }

        test("decodes byte by byte and with small output limits") {
            val body = "a\r\n0123456789\r\n3\r\nabc\r\n0\r\n\r\n".toByteArray()
            decodeAll(body, step = 1).first shouldBe "0123456789abc"
            decodeAll(body, maxData = 2).first shouldBe "0123456789abc"
        }

        test("rejects malformed framing") {
            for (bad in listOf("x\r\n", "5\r\nabcdeX\r\n", "ffffffffffffffffff\r\n", "\r\n", "5;\u0001\r\n")) {
                shouldThrow<HttpParseException> { decodeAll((bad + "0\r\n\r\n").toByteArray()) }
            }
        }

        test("round trip with random chunking") {
            val random = Random(7)
            repeat(200) {
                val data = ByteArray(random.nextInt(0, 5000)) { random.nextInt(32, 127).toByte() }
                val enc = ByteArrayOutputStream()
                var i = 0
                while (i < data.size) {
                    val n = minOf(random.nextInt(1, 700), data.size - i)
                    enc.write("${Integer.toHexString(n)}\r\n".toByteArray())
                    enc.write(data, i, n)
                    enc.write("\r\n".toByteArray())
                    i += n
                }
                enc.write("0\r\n\r\n".toByteArray())
                decodeAll(enc.toByteArray(), step = random.nextInt(1, 100)).first shouldBe String(data)
            }
        }
    })
