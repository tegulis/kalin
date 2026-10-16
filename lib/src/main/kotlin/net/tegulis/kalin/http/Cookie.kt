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
    /** Formats this cookie as a `Set-Cookie` header value. */
    public fun toSetCookieHeader(): String = buildString {
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
