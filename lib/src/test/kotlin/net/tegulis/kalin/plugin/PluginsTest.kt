package net.tegulis.kalin.plugin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.Base64
import java.util.concurrent.TimeUnit
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.plugin.bundled.GlobalHeadersConfig
import net.tegulis.kalin.plugin.bundled.RateLimitPlugin
import net.tegulis.kalin.testtools.testKalin

class PluginsTest :
    FunSpec({
        test("CORS with any host") {
            testKalin {
                app {
                    bundledPlugins.enableCors { cors -> cors.addRule { it.anyHost() } }
                    routes.get("/") { it.result("ok") }
                }
                client
                    .get("/") { header("Origin", "https://example.com") }
                    .header("Access-Control-Allow-Origin") shouldBe "*"
                client.get("/").header("Access-Control-Allow-Origin") shouldBe null
                client
                    .options("/") {
                        header("Origin", "https://example.com")
                        header("Access-Control-Request-Method", "PUT")
                        header("Access-Control-Request-Headers", "X-Custom")
                    }
                    .let {
                        it.code shouldBe 200
                        it.header("Access-Control-Allow-Methods") shouldBe "PUT"
                        it.header("Access-Control-Allow-Headers") shouldBe "X-Custom"
                    }
            }
        }

        test("CORS with allowed hosts, wildcards, credentials and exposed headers") {
            testKalin {
                app {
                    bundledPlugins.enableCors { cors ->
                        cors.addRule {
                            it.allowHost("example.com", "*.sub.example.org")
                            it.allowCredentials = true
                            it.exposeHeader("X-Exposed")
                        }
                    }
                    routes.get("/") { it.result("ok") }
                }
                client
                    .get("/") { header("Origin", "https://example.com") }
                    .let {
                        it.header("Access-Control-Allow-Origin") shouldBe "https://example.com"
                        it.header("Access-Control-Allow-Credentials") shouldBe "true"
                        it.header("Access-Control-Expose-Headers") shouldBe "X-Exposed"
                        it.header("Vary") shouldBe "Origin"
                    }
                client
                    .get("/") { header("Origin", "https://a.sub.example.org") }
                    .header("Access-Control-Allow-Origin") shouldBe "https://a.sub.example.org"
                client.get("/") { header("Origin", "https://evil.com") }.code shouldBe 400
            }
            shouldThrow<IllegalArgumentException> {
                Kalin.create { c ->
                    c.bundledPlugins.enableCors { cors ->
                        cors.addRule {
                            it.anyHost()
                            it.allowCredentials = true
                        }
                    }
                }
            }
        }

        test("basic auth plugin") {
            testKalin {
                app {
                    bundledPlugins.enableBasicAuth("user", "secret")
                    routes.get("/") { it.result("ok") }
                }
                client.get("/").let {
                    it.code shouldBe 401
                    it.header("WWW-Authenticate") shouldBe "Basic"
                }
                val token = Base64.getEncoder().encodeToString("user:secret".toByteArray())
                client.get("/") { header("Authorization", "Basic $token") }.bodyAsText() shouldBe "ok"
            }
        }

        test("global headers plugin") {
            testKalin {
                app {
                    bundledPlugins.enableGlobalHeaders {
                        it.xContentTypeOptionsNoSniff()
                        it.xFrameOptions(GlobalHeadersConfig.XFrameOptions.DENY)
                        it.referrerPolicy(GlobalHeadersConfig.ReferrerPolicy.NO_REFERRER)
                    }
                    routes.get("/") { it.result("ok") }
                }
                client.get("/").let {
                    it.header("X-Content-Type-Options") shouldBe "nosniff"
                    it.header("X-Frame-Options") shouldBe "DENY"
                    it.header("Referrer-Policy") shouldBe "no-referrer"
                }
            }
        }

        test("route overview plugin") {
            testKalin {
                app {
                    bundledPlugins.enableRouteOverview("/routes")
                    routes.get("/users/{id}") {}
                    routes.post("/users") {}
                }
                client.get("/routes").bodyAsText() shouldContain "/users/{id}"
                client.get("/routes") { header("Accept", "application/json") }.bodyAsText() shouldContain
                    "\"handlerType\":\"POST\""
            }
        }

        test("rate limit plugin") {
            testKalin {
                app {
                    registerPlugin(RateLimitPlugin())
                    routes.get("/") { ctx ->
                        ctx.with(RateLimitPlugin::class).requestPerTimeUnit(3, TimeUnit.HOURS)
                        ctx.result("ok")
                    }
                }
                repeat(3) { client.get("/").code shouldBe 200 }
                client.get("/").let {
                    it.code shouldBe 429
                    it.bodyAsText() shouldBe "Rate limit exceeded - Server allows 3 requests per hour."
                }
            }
        }

        test("redirect to lowercase paths and allowed methods plugins") {
            testKalin {
                app {
                    bundledPlugins.enableRedirectToLowercasePaths()
                    bundledPlugins.enableHttpAllowedMethodsOnRoutes()
                    routes.get("/my/path/{param}") { it.result(it.pathParam("param")) }
                    routes.post("/my/path/{param}") {}
                }
                client.get("/MY/Path/VALUE?x=1").let {
                    it.code shouldBe 301
                    it.header("Location") shouldBe "/my/path/VALUE?x=1"
                }
                client.options("/my/path/x").header("Access-Control-Allow-Methods") shouldBe "GET,POST,OPTIONS"
            }
        }

        test("custom plugins: initialization order, repeatable, config") {
            val order = ArrayList<String>()

            class Named(private val n: String, private val p: PluginPriority) : Plugin<Unit>() {
                override fun onInitialize(config: KalinConfig) {
                    order.add("init $n")
                }

                override fun onStart(config: KalinConfig) {
                    order.add("start $n")
                }

                override fun priority() = p

                override fun repeatable() = true
            }

            testKalin {
                app {
                    registerPlugin(Named("late", PluginPriority.LATE))
                    registerPlugin(Named("early", PluginPriority.EARLY))
                    registerPlugin(Named("normal", PluginPriority.NORMAL))
                    registerPlugin(Configured { it.greeting = "hello" })
                }
                client.get("/greet").bodyAsText() shouldBe "hello"
            }
            order shouldBe listOf("init early", "init normal", "init late", "start late", "start early", "start normal")
            shouldThrow<PluginAlreadyRegisteredException> {
                Kalin.create {
                    it.registerPlugin(RateLimitPlugin())
                    it.registerPlugin(RateLimitPlugin())
                }
            }
        }
    })

class Configured(userConfig: java.util.function.Consumer<Configured.Cfg>) : Plugin<Configured.Cfg>(userConfig, Cfg()) {
    class Cfg {
        var greeting = "hi"
    }

    override fun onInitialize(config: KalinConfig) {
        config.routes.get("/greet") { it.result(pluginConfig.greeting) }
    }
}
