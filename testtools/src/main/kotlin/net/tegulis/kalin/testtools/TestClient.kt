package net.tegulis.kalin.testtools

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.UUID
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.json.JsonMapper

/** A request being built inside `client.get(path) { ... }`. */
public class TestRequest internal constructor(private val jsonMapper: () -> JsonMapper) {
    internal val headers = ArrayList<Pair<String, String>>()
    internal val queryParams = ArrayList<Pair<String, String>>()
    internal val cookies = ArrayList<Pair<String, String>>()
    internal var body: ByteArray? = null

    /** The timeout for this request. */
    public var timeout: Duration = 10.seconds

    /** Adds a request header. */
    public fun header(name: String, value: String) {
        headers.add(name to value)
    }

    /** Adds a query parameter. */
    public fun queryParam(name: String, value: String) {
        queryParams.add(name to value)
    }

    /** Adds a cookie. */
    public fun cookie(name: String, value: String) {
        cookies.add(name to value)
    }

    /** Sets a text body (with `text/plain` unless a content type is set). */
    public fun body(text: String) {
        body = text.toByteArray(Charsets.UTF_8)
        if (headers.none { it.first.equals("Content-Type", true) }) header("Content-Type", "text/plain;charset=utf-8")
    }

    /** Sets a binary body. */
    public fun body(bytes: ByteArray, contentType: String = ContentType.OCTET_STREAM) {
        body = bytes
        if (headers.none { it.first.equals("Content-Type", true) }) header("Content-Type", contentType)
    }

    /** Sets a JSON body serialized with the app's JSON mapper. */
    public fun json(obj: Any?, type: KType) {
        body = jsonMapper().toJsonString(obj, type).toByteArray(Charsets.UTF_8)
        headers.removeIf { it.first.equals("Content-Type", true) }
        header("Content-Type", ContentType.JSON)
    }

    /** Sets a JSON body serialized with the app's JSON mapper. */
    public inline fun <reified T> json(obj: T): Unit = json(obj, typeOf<T>())

    /** Sets a raw JSON string body. */
    public fun jsonString(json: String) {
        body = json.toByteArray(Charsets.UTF_8)
        headers.removeIf { it.first.equals("Content-Type", true) }
        header("Content-Type", ContentType.JSON)
    }

    /** Sets a URL-encoded form body. */
    public fun form(vararg fields: Pair<String, String>) {
        body = fields.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }.toByteArray()
        headers.removeIf { it.first.equals("Content-Type", true) }
        header("Content-Type", ContentType.FORM_URLENCODED)
    }

    /** Sets a `multipart/form-data` body with [fields] and [files] (field name to file). */
    public fun multipart(fields: Map<String, String> = emptyMap(), files: List<MultipartFile> = emptyList()) {
        val boundary = "kalin-" + UUID.randomUUID().toString().replace("-", "")
        val out = ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        for (f in files) {
            w(
                "--$boundary\r\nContent-Disposition: form-data; name=\"${f.field}\"; filename=\"${f.fileName}\"\r\nContent-Type: ${f.contentType}\r\n\r\n"
            )
            out.write(f.content)
            w("\r\n")
        }
        w("--$boundary--\r\n")
        body = out.toByteArray()
        headers.removeIf { it.first.equals("Content-Type", true) }
        header("Content-Type", "multipart/form-data; boundary=$boundary")
    }

    internal fun target(path: String): String =
        if (queryParams.isEmpty()) path
        else
            path +
                (if (path.contains('?')) "&" else "?") +
                queryParams.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/** A file for [TestRequest.multipart]. */
public class MultipartFile(
    /** The form field name. */
    public val field: String,
    /** The file name. */
    public val fileName: String,
    /** The content. */
    public val content: ByteArray,
    /** The content type. */
    public val contentType: String = ContentType.OCTET_STREAM,
)

/** A response content type with optional parameters. */
public class ResponseContentType
internal constructor(
    /** The full header value. */
    public val value: String
) {
    /** The mime type without parameters, for example `text/plain`. */
    public val mimeType: String = value.substringBefore(';').trim()

    /** The parameters, for example `charset`. */
    public val parameters: Map<String, String> =
        value
            .split(';')
            .drop(1)
            .mapNotNull { p ->
                val eq = p.indexOf('=')
                if (eq < 0) null
                else p.substring(0, eq).trim().lowercase() to p.substring(eq + 1).trim().removeSurrounding("\"")
            }
            .toMap()

    /** The matching [ContentType] constant without parameters, or null if unknown. */
    public fun withoutParameters(): ContentType? = ContentType.contentType(mimeType)

    override fun toString(): String = value
}

/** Javalin-compatible body accessor (`response.body.string()`). */
public class ResponseBody internal constructor(private val bytes: ByteArray) {
    /** The body as UTF-8 text. */
    public fun string(): String = String(bytes, Charsets.UTF_8)

    /** The body bytes. */
    public fun bytes(): ByteArray = bytes
}

/** A response received by the [TestClient]. */
public class TestResponse
internal constructor(
    /** The status code. */
    public val code: Int,
    /** The headers in arrival order. */
    public val headerList: List<Pair<String, String>>,
    private val bodyBytes: ByteArray,
    private val jsonMapper: () -> JsonMapper,
) {
    /** The status. */
    public val status: HttpStatus
        get() = HttpStatus.forStatus(code)

    /** The status code (Javalin compatibility). */
    public fun code(): Int = code

    /** All headers by lowercase name. */
    public val headers: Map<String, List<String>> by lazy {
        val map = LinkedHashMap<String, MutableList<String>>()
        for ((k, v) in headerList) map.getOrPut(k.lowercase()) { ArrayList(1) }.add(v)
        map
    }

    /** The first value of header [name], or null. */
    public fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** The response content type, or null. */
    public fun contentType(): ResponseContentType? = header("Content-Type")?.let { ResponseContentType(it) }

    /** Cookies set by the response (name to value). */
    public val cookies: Map<String, String>
        get() =
            (headers["set-cookie"] ?: emptyList()).associate { c ->
                val pair = c.substringBefore(';')
                pair.substringBefore('=').trim() to pair.substringAfter('=', "").trim()
            }

    /** The body as text (charset from `Content-Type`, default UTF-8). */
    public fun bodyAsText(): String {
        val charset =
            contentType()?.parameters?.get("charset")?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bodyBytes, charset)
    }

    /** The body bytes. */
    public fun bodyAsBytes(): ByteArray = bodyBytes

    /** The body deserialized with the app's JSON mapper. */
    public fun <T> body(type: KType): T = jsonMapper().fromJsonString(bodyAsText(), type)

    /** The body deserialized with the app's JSON mapper. */
    public inline fun <reified T> body(): T = body(typeOf<T>())

    /** Javalin-compatible body accessor: `response.body.string()`. */
    public val body: ResponseBody
        get() = ResponseBody(bodyBytes)

    override fun toString(): String = "TestResponse($code, headers=$headerList, body=${bodyAsText().take(200)})"
}

/** The result of [TestClient.raw]: the raw bytes received and the parsed responses. */
public class RawResult
internal constructor(
    /** Everything received, as text. */
    public val text: String,
    /** The parsed responses, in order. */
    public val responses: List<TestResponse>,
    /** True if the server closed the connection. */
    public val closedByServer: Boolean,
) {
    /** The first response. */
    public val response: TestResponse
        get() = responses.first()
}
