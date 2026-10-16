package net.tegulis.kalin.examples

import net.tegulis.kalin.Kalin
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.rendering.jte.KalinJte

/** A page model used by `src/main/jte/page.jte`. */
data class Page(val title: String, val items: List<String>)

/** Templates precompiled at build time by the jte Gradle plugin (see build.gradle.kts). */
fun KalinConfig.templateRoutes() {
    fileRenderer(KalinJte())
    routes.get("/") { ctx ->
        ctx.render("page.jte", mapOf("page" to Page("Kalin + jte", listOf("fast", "typed", "precompiled"))))
    }
}

/**
 * `./gradlew :examples:run -Pexample=JteTemplates`. For hot reload use KalinJte(KalinJte.directoryTemplateEngine()).
 */
fun main() {
    Kalin.create { config -> config.templateRoutes() }.start(7070)
}
