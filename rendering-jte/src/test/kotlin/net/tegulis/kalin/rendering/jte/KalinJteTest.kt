package net.tegulis.kalin.rendering.jte

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import net.tegulis.kalin.testtools.testKalin

class KalinJteTest :
    FunSpec({
        val templates = Path.of("src/test/jte")

        // Compile into build/, not the module directory
        fun engine() = KalinJte.directoryTemplateEngine(templates.toString(), classDirectory = "build/jte-classes")

        test("renders .jte templates with hot reload engine, escaping user content") {
            testKalin {
                app {
                    fileRenderer(KalinJte(engine()))
                    routes.get("/") { ctx ->
                        ctx.render("hello.jte", mapOf("name" to "<Kalin>", "items" to listOf("a", "b")))
                    }
                }
                client.get("/").let {
                    it.contentType()!!.mimeType shouldBe "text/html"
                    it.bodyAsText() shouldContain "<h1>Hello &lt;Kalin&gt;!</h1>"
                    it.bodyAsText() shouldContain "<li>a</li><li>b</li>"
                }
            }
        }

        test("renders .kte templates") {
            testKalin {
                app {
                    fileRenderer(KalinJte(engine()))
                    routes.get("/") { ctx -> ctx.render("greeting.kte", mapOf("name" to "kte")) }
                }
                client.get("/").bodyAsText() shouldContain "<p>Kotlin template for kte</p>"
            }
        }

        test("the default engine loads precompiled templates; missing templates are server errors") {
            testKalin {
                app {
                    fileRenderer(KalinJte())
                    routes.get("/") { ctx -> ctx.render("missing.jte") }
                }
                client.get("/").code shouldBe 500
            }
        }

        test("renders to a string for non-streaming use") {
            val renderer = KalinJte(engine())
            testKalin {
                app {
                    routes.get("/") { ctx ->
                        ctx.result(
                            renderer.render("hello.jte", mapOf("name" to "s", "items" to emptyList<String>()), ctx)
                        )
                    }
                }
                client.get("/").bodyAsText() shouldContain "<h1>Hello s!</h1>"
            }
        }
    })
