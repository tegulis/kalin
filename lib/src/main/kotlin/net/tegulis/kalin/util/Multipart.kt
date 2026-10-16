package net.tegulis.kalin.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import net.tegulis.kalin.config.MultipartConfig
import net.tegulis.kalin.http.BadRequestResponse
import net.tegulis.kalin.http.ContentTooLargeResponse
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.UploadedFile

/** A streaming `multipart/form-data` parser (RFC 7578). */
internal object Multipart {
    class Parsed(
        val fields: Map<String, List<String>>,
        val files: Map<String, List<UploadedFile>>,
        private val temp: List<Path>,
    ) {
        fun cleanup() {
            for (p in temp) {
                try {
                    Files.deleteIfExists(p)
                } catch (_: IOException) {}
            }
        }
    }

    private class Part(
        val field: String,
        val fileName: String,
        val type: String?,
        val partHeaders: Map<String, List<String>>,
        val bytes: ByteArray?,
        val file: Path?,
        val length: Long,
    ) : UploadedFile {
        override fun fieldName() = field

        override fun filename() = fileName

        override fun contentType() = type

        override fun size() = length

        override fun content(): InputStream = bytes?.let { ByteArrayInputStream(it) } ?: Files.newInputStream(file!!)

        override fun headers() = partHeaders

        override fun toString() = "UploadedFile(field=$field, filename=$fileName, size=$length)"
    }

    fun boundary(contentType: String): String? =
        contentType
            .split(';')
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
            ?.substring(9)
            ?.trim()
            ?.removeSurrounding("\"")
            ?.ifEmpty { null }

    /**
     * Parses the multipart body of [ctx]. [maxRequestSize] (`http.maxRequestSize`) limits the body when
     * [MultipartConfig.maxTotalRequestSize] is -1.
     */
    fun parse(ctx: Context, config: MultipartConfig, maxRequestSize: Long): Parsed {
        val contentType = ctx.header(Header.CONTENT_TYPE) ?: throw BadRequestResponse("Missing Content-Type")
        val boundary = boundary(contentType) ?: throw BadRequestResponse("Missing multipart boundary")
        val maxTotal = if (config.maxTotalRequestSize >= 0) config.maxTotalRequestSize else maxRequestSize
        if (maxTotal >= 0 && ctx.exchange().requestContentLength > maxTotal)
            throw ContentTooLargeResponse("Multipart request exceeds $maxTotal bytes")
        val input = ctx.bodyInputStream()
        return parse(input, boundary, config, maxTotal)
    }

    fun parse(input: InputStream, boundary: String, config: MultipartConfig, maxTotal: Long): Parsed {
        val reader = DelimitedReader(input, ("\r\n--$boundary").toByteArray(Charsets.ISO_8859_1), maxTotal)
        val budget = MemoryBudget(config.maxInMemoryTotalSize())
        val fields = LinkedHashMap<String, MutableList<String>>()
        val files = LinkedHashMap<String, MutableList<UploadedFile>>()
        val temp = ArrayList<Path>()
        var sink: SpillingSink? = null
        var parts = 0
        try {
            // Preamble: the first delimiter has no leading CRLF
            reader.skipPreamble()
            while (true) {
                // after delimiter: "--" (end) or CRLF
                val a = reader.readByte()
                val b = reader.readByte()
                if (a == '-'.code && b == '-'.code) break
                if (a != '\r'.code || b != '\n'.code) throw BadRequestResponse("Malformed multipart body")
                if (++parts > config.maxParts)
                    throw ContentTooLargeResponse("Multipart request has more than ${config.maxParts} parts")
                val headers = reader.readHeaders()
                val disposition =
                    headers["content-disposition"]?.firstOrNull()
                        ?: throw BadRequestResponse("Missing Content-Disposition")
                val params = parseDisposition(disposition)
                val name = params["name"] ?: throw BadRequestResponse("Missing part name")
                val fileName = params["filename*"]?.let { decodeExtValue(it) } ?: params["filename"]
                val partType = headers["content-type"]?.firstOrNull()
                if (fileName == null) {
                    val bos = ByteArrayOutputStream()
                    reader.readPart(bos, 1024 * 1024)
                    val charset = partType?.let { charsetOf(it) } ?: Charsets.UTF_8
                    fields.getOrPut(name) { ArrayList(1) }.add(bos.toString(charset))
                } else {
                    val s = SpillingSink(config, budget, temp).also { sink = it }
                    reader.readPart(s, config.maxFileSize)
                    s.close()
                    sink = null
                    val part = Part(name, fileName, partType, headers, s.bytes(), s.file, s.size)
                    files.getOrPut(name) { ArrayList(1) }.add(part)
                }
            }
        } catch (e: Exception) {
            try {
                sink?.close()
            } catch (_: IOException) {}
            Parsed(fields, files, temp).cleanup()
            throw e
        }
        return Parsed(fields, files, temp)
    }

    private fun charsetOf(contentType: String) =
        contentType
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("charset=", true) }
            ?.substring(8)
            ?.let { runCatching { java.nio.charset.Charset.forName(it.removeSurrounding("\"")) }.getOrNull() }

    private fun parseDisposition(value: String): Map<String, String> {
        val result = HashMap<String, String>()
        var i = value.indexOf(';')
        while (i >= 0 && i < value.length) {
            i++
            while (i < value.length && value[i] == ' ') i++
            val eq = value.indexOf('=', i)
            if (eq < 0) break
            val key = value.substring(i, eq).trim().lowercase()
            var j = eq + 1
            val v: String
            if (j < value.length && value[j] == '"') {
                val sb = StringBuilder()
                j++
                while (j < value.length && value[j] != '"') {
                    if (value[j] == '\\' && j + 1 < value.length) j++
                    sb.append(value[j])
                    j++
                }
                v = sb.toString()
                j++
            } else {
                val end = value.indexOf(';', j).let { if (it < 0) value.length else it }
                v = value.substring(j, end).trim()
                j = end
            }
            result[key] = v
            i = value.indexOf(';', j)
        }
        return result
    }

    private fun decodeExtValue(v: String): String {
        // RFC 5987: charset'lang'percent-encoded
        val parts = v.split('\'', limit = 3)
        if (parts.size != 3) return v
        val charset = runCatching { java.nio.charset.Charset.forName(parts[0]) }.getOrDefault(Charsets.UTF_8)
        return try {
            java.net.URLDecoder.decode(parts[2].replace("+", "%2B"), charset)
        } catch (_: IllegalArgumentException) {
            throw BadRequestResponse("Malformed filename* parameter")
        }
    }

    /** Bytes of file parts currently held in memory, shared by all parts of one request. */
    private class MemoryBudget(val max: Long) {
        var used = 0L
    }

    /**
     * Collects a file part in memory and spills to a temp file when it grows larger than `maxInMemoryFileSize` or the
     * request's in-memory [budget] runs out.
     */
    private class SpillingSink(
        private val config: MultipartConfig,
        private val budget: MemoryBudget,
        private val temp: MutableList<Path>,
    ) : OutputStream() {
        private var memory: ByteArrayOutputStream? = ByteArrayOutputStream()
        var file: Path? = null
            private set

        private var fileOut: OutputStream? = null
        var size = 0L
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            size += len
            val mem = memory
            if (mem != null) {
                if (mem.size() + len <= config.maxInMemoryFileSize && budget.used + len <= budget.max) {
                    mem.write(b, off, len)
                    budget.used += len
                    return
                }
                budget.used -= mem.size()
                val dir = config.cacheDirectory?.let { Paths.get(it) }
                val f =
                    if (dir != null) Files.createTempFile(dir, "kalin-upload-", ".tmp")
                    else Files.createTempFile("kalin-upload-", ".tmp")
                temp.add(f)
                file = f
                val out = Files.newOutputStream(f).buffered()
                mem.writeTo(out)
                fileOut = out
                memory = null
            }
            fileOut!!.write(b, off, len)
        }

        fun bytes(): ByteArray? = memory?.toByteArray()

        override fun close() {
            fileOut?.close()
        }
    }

    /** Reads a stream split by a delimiter, with bounded lookahead. */
    private class DelimitedReader(
        private val input: InputStream,
        private val delimiter: ByteArray,
        private val maxTotal: Long,
    ) {
        private val buf = ByteArray(maxOf(16 * 1024, delimiter.size * 4))
        private var pos = 0
        private var limit = 0
        private var total = 0L

        private fun fill(): Boolean {
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, limit - pos)
                limit -= pos
                pos = 0
            }
            if (limit == buf.size) return true
            val n = input.read(buf, limit, buf.size - limit)
            if (n < 0) return false
            total += n
            if (maxTotal >= 0 && total > maxTotal)
                throw ContentTooLargeResponse("Multipart request exceeds $maxTotal bytes")
            limit += n
            return true
        }

        fun readByte(): Int {
            if (pos == limit && !fill()) throw BadRequestResponse("Unexpected end of multipart body")
            if (pos == limit) throw BadRequestResponse("Unexpected end of multipart body")
            return buf[pos++].toInt() and 0xFF
        }

        /** Skips up to and including the first delimiter (which may lack the leading CRLF). */
        fun skipPreamble() {
            val first = delimiter.copyOfRange(2, delimiter.size)
            // Fast path: body starts with --boundary
            while (limit - pos < first.size) if (!fill()) throw BadRequestResponse("Empty multipart body")
            if (matches(first, pos)) {
                pos += first.size
                return
            }
            readPart(OutputStream.nullOutputStream(), -1)
        }

        private fun matches(pattern: ByteArray, at: Int): Boolean {
            for (i in pattern.indices) if (buf[at + i] != pattern[i]) return false
            return true
        }

        fun readHeaders(): Map<String, List<String>> {
            val headers = LinkedHashMap<String, MutableList<String>>()
            var size = 0
            while (true) {
                val line = StringBuilder()
                while (true) {
                    val c = readByte()
                    if (++size > 16 * 1024) throw BadRequestResponse("Multipart headers too large")
                    if (c == '\n'.code) break
                    if (c != '\r'.code) line.append(c.toChar())
                }
                if (line.isEmpty()) return headers
                val colon = line.indexOf(":")
                if (colon <= 0) throw BadRequestResponse("Malformed multipart header")
                val raw = line.substring(colon + 1).trim()
                val value = String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                headers.getOrPut(line.substring(0, colon).trim().lowercase()) { ArrayList(1) }.add(value)
            }
        }

        /** Copies bytes up to the next delimiter to [out] and consumes the delimiter. */
        fun readPart(out: OutputStream, maxSize: Long) {
            var written = 0L
            while (true) {
                // search delimiter in [pos, limit)
                val searchEnd = limit - delimiter.size
                var i = pos
                var found = -1
                while (i <= searchEnd) {
                    if (buf[i] == delimiter[0] && matches(delimiter, i)) {
                        found = i
                        break
                    }
                    i++
                }
                if (found >= 0) {
                    val n = found - pos
                    written += n
                    if (maxSize >= 0 && written > maxSize)
                        throw ContentTooLargeResponse("Uploaded file exceeds $maxSize bytes")
                    out.write(buf, pos, n)
                    pos = found + delimiter.size
                    return
                }
                // emit everything that cannot be the start of a delimiter
                val safe = maxOf(pos, limit - delimiter.size + 1)
                val n = safe - pos
                if (n > 0) {
                    written += n
                    if (maxSize >= 0 && written > maxSize)
                        throw ContentTooLargeResponse("Uploaded file exceeds $maxSize bytes")
                    out.write(buf, pos, n)
                    pos = safe
                }
                if (!fill() || (pos == 0 && limit == buf.size && n == 0)) {
                    if (pos == limit || limit - pos < delimiter.size)
                        throw BadRequestResponse("Unexpected end of multipart body")
                }
            }
        }
    }
}
