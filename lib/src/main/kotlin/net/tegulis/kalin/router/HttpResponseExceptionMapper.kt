package net.tegulis.kalin.router

import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.Header
import net.tegulis.kalin.http.HttpResponseException
import net.tegulis.kalin.http.MethodNotAllowedResponse
import net.tegulis.kalin.json.SimpleJson

/** Default answer for [HttpResponseException]s: JSON if the client accepts it, else plain text (HTML if accepted). */
public object HttpResponseExceptionMapper {
    private const val DOCS_URL = "https://github.com/tegulis/kalin#"

    /** Writes the default response for [e]. */
    @JvmStatic
    public fun handle(e: HttpResponseException, ctx: Context) {
        val accept = ctx.header(Header.ACCEPT)
        val responseType = ctx.responseHeader(Header.CONTENT_TYPE)
        fun accepts(type: String) = accept?.contains(type) == true || responseType == type
        when {
            accepts(ContentType.HTML) -> answer(ctx, e, plainResult(e)).contentType(ContentType.HTML)
            accepts(ContentType.JSON) -> answer(ctx, e, jsonResult(e)).contentType(ContentType.JSON)
            else -> answer(ctx, e, plainResult(e)).contentType(ContentType.PLAIN)
        }
    }

    private fun answer(ctx: Context, e: HttpResponseException, body: String): Context {
        ctx.status(e.status).result(body)
        if (e is MethodNotAllowedResponse) {
            (e.details["availableMethods"] ?: e.details["Available methods"])?.let { ctx.header(Header.ALLOW, it) }
        }
        return ctx
    }

    /** The JSON body for [e]. */
    @JvmStatic
    public fun jsonResult(e: HttpResponseException): String =
        "{\n" +
            "    \"title\": ${SimpleJson.quote(e.message ?: "")},\n" +
            "    \"status\": ${e.status},\n" +
            "    \"type\": ${SimpleJson.quote(DOCS_URL + e.javaClass.simpleName.lowercase())},\n" +
            "    \"details\": {${e.details.entries.joinToString(",") { "${SimpleJson.quote(it.key)}:${SimpleJson.quote(it.value)}" }}}\n" +
            "}"

    private fun plainResult(e: HttpResponseException): String =
        if (e.details.isEmpty()) "${e.message}"
        else "${e.message}\n" + e.details.entries.joinToString("") { "\n${it.key}:\n${it.value}" } + "\n"
}
