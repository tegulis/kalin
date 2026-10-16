package net.tegulis.kalin.http

import java.util.concurrent.ConcurrentHashMap

/**
 * The type of a handler: an HTTP method (`GET`, `POST`, custom methods such as `PROPFIND`) or a lifecycle phase
 * (`BEFORE`, `AFTER`, ...).
 *
 * Instances are interned: use [findOrCreate] for custom methods.
 */
public class HandlerType
private constructor(
    /** The name, for HTTP methods the method token. */
    @JvmField public val name: String,
    /** True if this type is an HTTP method, false for lifecycle phases. */
    @JvmField public val isHttpMethod: Boolean,
) {
    /** Returns the name (Javalin compatibility). */
    public fun name(): String = name

    override fun toString(): String = name

    override fun equals(other: Any?): Boolean =
        this === other || (other is HandlerType && other.name == name && other.isHttpMethod == isHttpMethod)

    override fun hashCode(): Int = name.hashCode()

    public companion object {
        private val methods = ConcurrentHashMap<String, HandlerType>()

        private fun register(name: String, http: Boolean = true): HandlerType =
            HandlerType(name, http).also { if (http) methods[name] = it }

        @JvmField public val GET: HandlerType = register("GET")
        @JvmField public val POST: HandlerType = register("POST")
        @JvmField public val QUERY: HandlerType = register("QUERY")
        @JvmField public val PUT: HandlerType = register("PUT")
        @JvmField public val PATCH: HandlerType = register("PATCH")
        @JvmField public val DELETE: HandlerType = register("DELETE")
        @JvmField public val HEAD: HandlerType = register("HEAD")
        @JvmField public val TRACE: HandlerType = register("TRACE")
        @JvmField public val CONNECT: HandlerType = register("CONNECT")
        @JvmField public val OPTIONS: HandlerType = register("OPTIONS")
        @JvmField public val BEFORE: HandlerType = register("BEFORE", http = false)
        @JvmField public val BEFORE_MATCHED: HandlerType = register("BEFORE_MATCHED", http = false)
        @JvmField public val AFTER_MATCHED: HandlerType = register("AFTER_MATCHED", http = false)
        @JvmField public val AFTER: HandlerType = register("AFTER", http = false)

        private val DEFAULTS =
            listOf(
                GET,
                POST,
                QUERY,
                PUT,
                PATCH,
                DELETE,
                HEAD,
                TRACE,
                CONNECT,
                OPTIONS,
                BEFORE,
                BEFORE_MATCHED,
                AFTER_MATCHED,
                AFTER,
            )
        private val COMMON = listOf(GET, POST, QUERY, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE)

        /**
         * Returns the handler type for the HTTP method [name], creating it if needed. Custom method names must consist
         * of uppercase letters only.
         */
        @JvmStatic
        public fun findOrCreate(name: String): HandlerType {
            methods[name]?.let {
                return it
            }
            require(name.isNotEmpty() && name.all { it in 'A'..'Z' }) { "Invalid HTTP method name: $name" }
            return methods.computeIfAbsent(name) { HandlerType(it, true) }
        }

        /** Returns the registered handler type for [name], or an unregistered instance. */
        @JvmStatic public fun findOrDefault(name: String): HandlerType = methods[name] ?: HandlerType(name, true)

        /** Returns the handler type for an HTTP method name if it is known, else null. */
        @JvmStatic public fun findByName(name: String): HandlerType? = methods[name]

        /** All built-in handler types. */
        @JvmStatic public fun values(): List<HandlerType> = DEFAULTS

        /** The common HTTP methods. */
        @JvmStatic public fun commonHttp(): List<HandlerType> = COMMON
    }
}
