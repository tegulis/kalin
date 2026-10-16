package net.tegulis.kalin.rendering.jte

import gg.jte.ContentType
import gg.jte.TemplateEngine
import gg.jte.resolve.DirectoryCodeResolver
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URLClassLoader
import java.nio.file.Path
import net.tegulis.kalin.testtools.testKalin

class KalinJteTest :
    FunSpec({
        val templates = Path.of("src/test/jte")

        test("renders .jte templates with hot reload engine, escaping user content") {
            testKalin {
                app {
                    fileRenderer(KalinJte(KalinJte.directoryTemplateEngine(templates.toString())))
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
                    fileRenderer(KalinJte(KalinJte.directoryTemplateEngine(templates.toString())))
                    routes.get("/") { ctx -> ctx.render("greeting.kte", mapOf("name" to "kte")) }
                }
                client.get("/").bodyAsText() shouldContain "<p>Kotlin template for kte</p>"
            }
        }

        test("renders precompiled templates") {
            val classes = tempdir().toPath()
            val sources = tempdir().toPath()
            java.nio.file.Files.copy(templates.resolve("hello.jte"), sources.resolve("hello.jte"))
            val compiler = TemplateEngine.create(DirectoryCodeResolver(sources), classes, ContentType.Html)
            compiler.setBinaryStaticContent(true)
            compiler.setClassPath(KalinJte.compileClassPath())
            compiler.precompileAll(listOf("hello.jte"))
            val loader = URLClassLoader(arrayOf(classes.toUri().toURL()), javaClass.classLoader)
            val precompiled = TemplateEngine.createPrecompiled(classes, ContentType.Html, loader)
            testKalin {
                app {
                    fileRenderer(KalinJte(precompiled))
                    routes.get("/") { ctx ->
                        ctx.render("hello.jte", mapOf("name" to "precompiled", "items" to listOf("x")))
                    }
                    routes.get("/string") { ctx ->
                        ctx.html(
                            KalinJte(precompiled)
                                .render("hello.jte", mapOf("name" to "s", "items" to emptyList<String>()), ctx)
                        )
                    }
                }
                client.get("/").bodyAsText() shouldContain "<h1>Hello precompiled!</h1>"
                client.get("/string").bodyAsText() shouldContain "<h1>Hello s!</h1>"
            }
        }
    })
