package net.tegulis.kalin.ktx

import kotlin.reflect.typeOf
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.http.HttpStatus

/*
 * Context-parameter helpers for handlers written with `handler { }`. They are additions to the Javalin-style
 * `{ ctx -> }` API, never replacements. Each helper takes the request [Context] from the calling context.
 */

/** Creates a [Handler] whose body has the request [Context] as context parameter. */
public fun handler(block: context(Context) () -> Unit): Handler = Handler { ctx -> context(ctx) { block() } }

/** The current request context. */
context(ctx: Context)
public val request: Context
    get() = ctx

/** The path parameter [name]. */
context(ctx: Context)
public fun pathParam(name: String): String = ctx.pathParam(name)

/** The query parameter [name]. */
context(ctx: Context)
public fun queryParam(name: String): String? = ctx.queryParam(name)

/** The form parameter [name]. */
context(ctx: Context)
public fun formParam(name: String): String? = ctx.formParam(name)

/** The request header [name]. */
context(ctx: Context)
public fun header(name: String): String? = ctx.header(name)

/** The request body deserialized from JSON. */
context(ctx: Context)
public inline fun <reified T> body(): T = ctx.bodyAsClass(typeOf<T>())

/** The request body as text. */
context(ctx: Context)
public fun bodyText(): String = ctx.body()

/** Responds with [value] as JSON. */
context(ctx: Context)
public inline fun <reified T> respondJson(value: T) {
    ctx.json(value, typeOf<T>())
}

/** Responds with [text]. */
context(ctx: Context)
public fun respondText(text: String) {
    ctx.result(text)
}

/** Sets the response status. */
context(ctx: Context)
public fun respondStatus(status: HttpStatus) {
    ctx.status(status)
}
