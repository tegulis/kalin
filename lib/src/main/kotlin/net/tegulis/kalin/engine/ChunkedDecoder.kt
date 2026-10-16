package net.tegulis.kalin.engine

/** Receives decoded body bytes. The slice is only valid during the call. */
internal fun interface DataSink {
    fun data(buf: ByteArray, off: Int, len: Int)
}

/**
 * Incremental decoder for `Transfer-Encoding: chunked` (RFC 9112 §7.1). Chunk extensions and trailer fields are parsed
 * and discarded, within limits.
 */
internal class ChunkedDecoder(private val limits: Limits) {
    private var state = SIZE
    private var chunkRemaining = 0L
    private var sizeDigits = 0
    private var lineBytes = 0
    private var trailerBytes = 0
    private var trailerLineEmpty = true

    val done: Boolean
        get() = state == DONE

    /**
     * Decodes from `src[off, off+len)`, passing at most [maxData] data bytes to [sink]. Returns the number of source
     * bytes consumed. Stops early when [maxData] bytes were produced or the body is complete.
     */
    fun decode(src: ByteArray, off: Int, len: Int, maxData: Int, sink: DataSink): Int {
        var i = off
        val end = off + len
        var produced = 0
        while (i < end && state != DONE) {
            when (state) {
                SIZE -> {
                    val c = src[i].toInt() and 0xFF
                    val digit = hex(c)
                    if (digit >= 0) {
                        if (++sizeDigits > 15) throw HttpParseException(400, "Chunk size too large")
                        chunkRemaining = chunkRemaining * 16 + digit
                        i++
                    } else if (sizeDigits == 0) {
                        throw HttpParseException(400, "Invalid chunk size")
                    } else if (c == ';'.code || c == ' '.code || c == '\t'.code) {
                        state = EXT
                        lineBytes = 0
                        i++
                    } else if (c == '\r'.code) {
                        state = SIZE_LF
                        i++
                    } else if (c == '\n'.code) {
                        i++
                        endOfSizeLine()
                    } else {
                        throw HttpParseException(400, "Invalid chunk size")
                    }
                }
                EXT -> {
                    val c = src[i].toInt() and 0xFF
                    i++
                    if (c == '\n'.code) {
                        endOfSizeLine()
                    } else if (c == '\r'.code) {
                        state = SIZE_LF
                    } else {
                        if (c < 0x20 && c != '\t'.code || c == 0x7F)
                            throw HttpParseException(400, "Invalid chunk extension")
                        if (++lineBytes > limits.maxChunkLineSize)
                            throw HttpParseException(400, "Chunk extension too long")
                    }
                }
                SIZE_LF -> {
                    if (src[i] != HttpParser.LF) throw HttpParseException(400, "Invalid chunk line ending")
                    i++
                    endOfSizeLine()
                }
                DATA -> {
                    val room = maxData - produced
                    if (room <= 0) return i - off
                    val n = minOf(chunkRemaining, (end - i).toLong(), room.toLong()).toInt()
                    sink.data(src, i, n)
                    produced += n
                    i += n
                    chunkRemaining -= n
                    if (chunkRemaining == 0L) state = DATA_CR
                }
                DATA_CR -> {
                    val b = src[i]
                    i++
                    state =
                        when (b) {
                            HttpParser.CR -> DATA_LF
                            HttpParser.LF -> SIZE.also { resetSize() }
                            else -> throw HttpParseException(400, "Missing CRLF after chunk data")
                        }
                }
                DATA_LF -> {
                    if (src[i] != HttpParser.LF) throw HttpParseException(400, "Missing CRLF after chunk data")
                    i++
                    state = SIZE
                    resetSize()
                }
                TRAILER -> {
                    val b = src[i]
                    i++
                    if (++trailerBytes > limits.maxTrailerSize)
                        throw HttpParseException(431, "Trailer section too large")
                    when (b) {
                        HttpParser.LF -> {
                            if (trailerLineEmpty) state = DONE
                            trailerLineEmpty = true
                        }
                        HttpParser.CR -> {}
                        else -> {
                            val c = b.toInt() and 0xFF
                            if (c < 0x20 && c != '\t'.code || c == 0x7F)
                                throw HttpParseException(400, "Invalid trailer")
                            trailerLineEmpty = false
                        }
                    }
                }
            }
        }
        return i - off
    }

    private fun endOfSizeLine() {
        if (chunkRemaining == 0L) {
            state = TRAILER
            trailerLineEmpty = true
        } else {
            state = DATA
        }
    }

    private fun resetSize() {
        chunkRemaining = 0
        sizeDigits = 0
    }

    private fun hex(c: Int): Int =
        when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }

    private companion object {
        const val SIZE = 0
        const val EXT = 1
        const val SIZE_LF = 2
        const val DATA = 3
        const val DATA_CR = 4
        const val DATA_LF = 5
        const val TRAILER = 6
        const val DONE = 7
    }
}
