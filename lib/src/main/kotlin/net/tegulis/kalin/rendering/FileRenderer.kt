package net.tegulis.kalin.rendering

import java.io.OutputStream
import net.tegulis.kalin.http.Context

/** Renders templates for `ctx.render(...)`. Plug any template engine in through `config.fileRenderer(...)`. */
public fun interface FileRenderer {
    /** Renders [filePath] with [model] to a string. */
    public fun render(filePath: String, model: Map<String, Any?>, ctx: Context): String
}

/** A [FileRenderer] that can write bytes straight to the response, without an intermediate string. */
public interface StreamingFileRenderer : FileRenderer {
    /** Renders [filePath] with [model] to [out]. */
    public fun render(filePath: String, model: Map<String, Any?>, ctx: Context, out: OutputStream)
}

/** The renderer used when none is configured. */
internal object MissingFileRenderer : FileRenderer {
    override fun render(filePath: String, model: Map<String, Any?>, ctx: Context): String =
        throw IllegalStateException(
            "No FileRenderer configured. Use config.fileRenderer(...), for example KalinJte from kalin-rendering-jte."
        )
}
