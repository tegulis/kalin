package net.tegulis.kalin.http

/** The `SameSite` cookie attribute. */
public enum class SameSite(
    /** The attribute as written in `Set-Cookie`. */
    public val value: String
) {
    NONE("SameSite=None"),
    STRICT("SameSite=Strict"),
    LAX("SameSite=Lax");

    override fun toString(): String = value
}

/** A response cookie. */
public data class Cookie
@JvmOverloads
constructor(
    var name: String,
    var value: String,
    var path: String = "/",
    var maxAge: Int = -1,
    var secure: Boolean = false,
    var isHttpOnly: Boolean = false,
    var domain: String? = null,
    var sameSite: SameSite? = null,
    var partitioned: Boolean = false,
) {
    /**
     * Formats this cookie as a `Set-Cookie` header value. Throws [IllegalArgumentException] for names, values or
     * attributes that are not allowed by RFC 6265 (they could inject attributes into the header).
     */
    public fun toSetCookieHeader(): String = buildString {
        require(name.isNotEmpty() && name.all { it.code in 0x21..0x7E && it !in "()<>@,;:\\\"/[]?={}" }) {
            "Invalid cookie name: '$name'"
        }
        require(value.all { it.code in 0x21..0x7E && it !in "\",;\\" }) {
            "Invalid cookie value for '$name' (encode it first)"
        }
        require(path.none { it == ';' || it.code < 0x20 } && domain?.any { it == ';' || it.code < 0x20 } != true) {
            "Invalid cookie path or domain"
        }
        append(name).append('=').append(value)
        if (path.isNotEmpty()) append("; Path=").append(path)
        domain?.let { append("; Domain=").append(it) }
        if (maxAge >= 0) {
            append("; Max-Age=").append(maxAge)
            append("; Expires=")
            append(
                net.tegulis.kalin.util.HttpDates.format(
                    if (maxAge == 0) 0L else System.currentTimeMillis() + maxAge * 1000L
                )
            )
        }
        if (secure) append("; Secure")
        if (isHttpOnly) append("; HttpOnly")
        sameSite?.let { append("; ").append(it.value) }
        if (partitioned) append("; Partitioned")
    }
}
