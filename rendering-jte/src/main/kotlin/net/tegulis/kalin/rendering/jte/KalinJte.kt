package net.tegulis.kalin.rendering.jte

import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.TemplateOutput
import gg.jte.output.StringOutput
import gg.jte.resolve.DirectoryCodeResolver
import java.io.OutputStream
import java.nio.file.Path
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.rendering.StreamingFileRenderer

/**
 * A [net.tegulis.kalin.rendering.FileRenderer] for [jte](https://jte.gg) templates (`.jte` and `.kte`).
 *
 * ```
 * Kalin.create { config ->
 *     config.fileRenderer(KalinJte())                                        // precompiled (production)
 *     // config.fileRenderer(KalinJte(KalinJte.directoryTemplateEngine()))  // hot reload from src/main/jte (dev)
 *     config.routes.get("/") { ctx -> ctx.render("index.kte", mapOf("user" to user)) }
 * }
 * ```
 *
 * Responses are rendered straight to the response stream as UTF-8 (with pre-encoded static parts for precompiled
 * templates built with `binaryStaticContent`).
 */
public class KalinJte
@JvmOverloads
constructor(
    /** The jte engine. */
    public val templateEngine: TemplateEngine = precompiledTemplateEngine()
) : StreamingFileRenderer {
    override fun render(filePath: String, model: Map<String, Any?>, ctx: Context): String {
        val output = StringOutput()
        templateEngine.render(filePath, model, output)
        return output.toString()
    }

    override fun render(filePath: String, model: Map<String, Any?>, ctx: Context, out: OutputStream) {
        templateEngine.render(filePath, model, Utf8StreamOutput(out))
    }

    public companion object {
        /** A precompiled engine loading generated template classes from the classpath (production). */
        @JvmStatic
        @JvmOverloads
        public fun precompiledTemplateEngine(contentType: ContentType = ContentType.Html): TemplateEngine =
            TemplateEngine.createPrecompiled(contentType)

        /**
         * An engine that compiles templates from [path] on demand and reloads them when they change (development).
         * Kotlin `.kte` templates additionally need `gg.jte:jte-kotlin` on the classpath. Compiled template classes are
         * written to [classDirectory] (jte's default `jte-classes`, relative to the working directory).
         */
        @JvmStatic
        @JvmOverloads
        public fun directoryTemplateEngine(
            path: String = "src/main/jte",
            contentType: ContentType = ContentType.Html,
            classDirectory: String = "jte-classes",
        ): TemplateEngine =
            TemplateEngine.create(DirectoryCodeResolver(Path.of(path)), Path.of(classDirectory), contentType).also {
                it.setClassPath(compileClassPath())
            }

        /**
         * The class path used to compile templates at runtime: `java.class.path` plus the jars of the Kotlin standard
         * library, jte and Kalin (which may be missing from `java.class.path`, for example in test workers).
         */
        @JvmStatic
        public fun compileClassPath(): List<String> {
            val fromProperty = System.getProperty("java.class.path").orEmpty().split(java.io.File.pathSeparatorChar)
            val fromClasses =
                listOf(Unit::class.java, TemplateEngine::class.java, TemplateOutput::class.java, Context::class.java)
                    .mapNotNull {
                        runCatching { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.getOrNull()
                    }
            return (fromProperty + fromClasses).filter { it.isNotBlank() }.distinct()
        }
    }
}

/** A jte output writing UTF-8 to a stream. */
internal class Utf8StreamOutput(private val out: OutputStream) : TemplateOutput {
    override fun writeContent(value: String) {
        out.write(value.toByteArray(Charsets.UTF_8))
    }

    override fun writeContent(value: String, beginIndex: Int, endIndex: Int) {
        out.write(value.substring(beginIndex, endIndex).toByteArray(Charsets.UTF_8))
    }

    override fun writeBinaryContent(value: ByteArray) {
        out.write(value)
    }
}
