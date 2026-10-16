package net.tegulis.kalin.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CopyOnWriteArrayList
import net.tegulis.kalin.apibuilder.crud
import net.tegulis.kalin.apibuilder.get
import net.tegulis.kalin.apibuilder.path
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.testtools.testKalin

class LifecycleTest :
    FunSpec({
        test("beforeMatched and afterMatched only run for matched requests") {
            testKalin {
                app {
                    routes.before { it.result("foo") }
                    routes.beforeMatched { it.result("before-matched") }
                    routes.get("/hello") { it.result(it.result() + "-hello") }
                    routes.afterMatched { it.result(it.result() + "-after-matched") }
                    routes.after { it.result(it.result() + "!") }
                }
                client.get("/other-path").let {
                    it.code shouldBe 404
                    it.bodyAsText() shouldBe "Endpoint GET /other-path not found!"
                }
                client.get("/hello").bodyAsText() shouldBe "before-matched-hello-after-matched!"
            }
        }

        test("beforeMatched gets path params of the endpoint") {
            testKalin {
                app {
                    routes.before { it.header("X-Always", "true") }
                    routes.beforeMatched { it.header("X-Before-Star", "true") }
                    routes.beforeMatched("/sub/{p}*") { it.header("X-Before-Sub-Curly", it.pathParam("p")) }
                    routes.beforeMatched("/angle/<a>") { it.header("X-Before-Angle", it.pathParam("a")) }
                    routes.afterMatched { it.header("X-After-Star", "true") }
                    routes.get("/root") { it.result("root") }
                    routes.get("/sub/id/other/stuff") { it.result("id-other-stuff") }
                    routes.get("/angle/i/see/slashes") { it.result("i-see-slashes") }
                    routes.get("/users/{id}") { it.result("user") }
                    routes.beforeMatched("/users/*") { it.header("X-Id", it.pathParam("id")) }
                }
                client.get("/sub/id/fake").let {
                    it.code shouldBe 404
                    it.header("X-Always") shouldBe "true"
                    it.header("X-Before-Star") shouldBe null
                }
                client.get("/sub/id/other/stuff").let {
                    it.bodyAsText() shouldBe "id-other-stuff"
                    it.header("X-Before-Sub-Curly") shouldBe "id"
                    it.header("X-After-Star") shouldBe "true"
                }
                client.get("/angle/i/see/slashes").header("X-Before-Angle") shouldBe "i/see/slashes"
                client.get("/users/42").header("X-Id") shouldBe "42"
            }
        }

        test("beforeMatched can skip remaining handlers") {
            testKalin {
                app {
                    routes.beforeMatched {
                        it.result("static-before")
                        it.skipRemainingHandlers()
                    }
                    routes.get("/hello") { it.result("hello") }
                }
                client.get("/hello").bodyAsText() shouldBe "static-before"
                client.get("/other").code shouldBe 404
            }
        }

        test("roles with beforeMatched access manager") {
            testKalin {
                app {
                    routes.beforeMatched { ctx ->
                        val role: RouteRole? = ctx.queryParam("role")?.let { MyRole.valueOf(it) }
                        if (role !in ctx.routeRoles()) throw UnauthorizedResponse()
                    }
                    routes.get("/secured", { it.result("Hello") }, MyRole.ROLE_ONE, MyRole.ROLE_TWO)
                    routes.apiBuilder {
                        path("/api", listOf(MyRole.ROLE_ONE)) {
                            get("/a") { it.result("a" + it.routeRoles().size) }
                            path("/admin", listOf(MyRole.ROLE_TWO)) {
                                get("/b") { it.result("b" + it.routeRoles().size) }
                            }
                        }
                        path("/users/{userId}") { crud(UserController(), MyRole.ROLE_ONE) }
                    }
                }
                client.get("/secured") { queryParam("role", "ROLE_ONE") }.bodyAsText() shouldBe "Hello"
                client
                    .get("/secured") { queryParam("role", "ROLE_THREE") }
                    .let {
                        it.code shouldBe 401
                        it.bodyAsText() shouldBe "Unauthorized"
                    }
                client.get("/api/a") { queryParam("role", "ROLE_ONE") }.bodyAsText() shouldBe "a1"
                client.get("/api/admin/b") { queryParam("role", "ROLE_TWO") }.bodyAsText() shouldBe "b2"
                client.get("/users/1") { queryParam("role", "ROLE_ONE") }.bodyAsText() shouldBe "My single user: 1"
                client.get("/users/1") { queryParam("role", "ROLE_TWO") }.code shouldBe 401
            }
        }

        test("crud handler routes") {
            testKalin {
                app { routes.apiBuilder { crud("users/{user-id}", UserController()) } }
                client.get("/users").bodyAsText() shouldBe "All my users"
                client.get("/users/1").bodyAsText() shouldBe "My single user: 1"
                client.post("/users").code shouldBe 201
                client.patch("/users/1").code shouldBe 204
                client.delete("/users/1").code shouldBe 204
            }
        }

        test("exception mapping: default, mapped, most specific, typed") {
            testKalin {
                app {
                    routes.get("/unmapped") { throw Exception() }
                    routes.get("/mapped") { throw IllegalStateException() }
                    routes.get("/typed") { throw TypedException() }
                    routes.get("/bad") { throw BadRequestResponse() }
                    routes.exception(IllegalStateException::class.java) { _, ctx -> ctx.result("It's been handled.") }
                    routes.exception(TypedException::class.java) { e, ctx -> ctx.result(e.proof()) }
                }
                client.get("/unmapped").let {
                    it.code shouldBe 500
                    it.bodyAsText() shouldBe "Server Error"
                }
                client.get("/mapped").let {
                    it.code shouldBe 200
                    it.bodyAsText() shouldBe "It's been handled."
                }
                client.get("/typed").bodyAsText() shouldBe "I'm so typed"
                client.get("/bad").code shouldBe 400
            }
        }

        test("catch-all mapper does not override HttpResponseExceptions or 404") {
            testKalin {
                app {
                    routes.exception(Exception::class.java) { _, ctx -> ctx.status(500) }
                    routes.get("/") { throw BadRequestResponse() }
                }
                client.get("/").code shouldBe 400
                client.get("/not-found").code shouldBe 404
            }
        }

        test("exceptions in exception handlers use the default handler") {
            testKalin {
                app {
                    routes.exception(Exception::class.java) { _, _ -> throw IllegalArgumentException("in handler") }
                    routes.get("/") { throw NumberFormatException() }
                }
                client.get("/").let {
                    it.code shouldBe 500
                    it.bodyAsText() shouldBe "Server Error"
                }
            }
        }

        test("after handlers run after exceptions, before handlers skip endpoints") {
            val log = CopyOnWriteArrayList<String>()
            testKalin {
                app {
                    routes.before { log.add("before1") }
                    routes.before { throw UnauthorizedResponse() }
                    routes.before { log.add("before3") }
                    routes.get("/") { log.add("endpoint") }
                    routes.after { log.add("after") }
                }
                client.get("/").code shouldBe 401
                log shouldBe listOf("before1", "after")
            }
        }

        test("default responses: JSON when accepted, plain otherwise, quotes escaped") {
            testKalin {
                app {
                    routes.get("/") { throw ForbiddenResponse("""Off "limits"!""") }
                    routes.get("/details") { throw BadRequestResponse("Bad", mapOf("field" to "value")) }
                }
                client
                    .get("/") { header("Accept", "application/json") }
                    .let {
                        it.code shouldBe 403
                        it.contentType()!!.mimeType shouldBe "application/json"
                        it.bodyAsText() shouldContain "\"title\": \"Off \\\"limits\\\"!\""
                        it.bodyAsText() shouldContain "\"status\": 403"
                    }
                client.get("/").bodyAsText() shouldBe "Off \"limits\"!"
                client.get("/") { header("Accept", "text/html") }.contentType()!!.mimeType shouldBe "text/html"
                client.get("/details").bodyAsText() shouldBe "Bad\n\nfield:\nvalue\n"
                client.get("/details") { header("Accept", "application/json") }.bodyAsText() shouldContain
                    "\"details\": {\"field\":\"value\"}"
            }
        }

        test("error mappers") {
            testKalin {
                app {
                    routes.get("/exception") { throw RuntimeException() }
                    routes.get("/555") { throw HttpResponseException(555, "Error 555") }
                    routes.get("/html") { it.status(500).result("Error!") }
                    routes.error(404) { it.result("Custom 404 page") }
                    routes.error(HttpStatus.INTERNAL_SERVER_ERROR) {
                        if (it.path() == "/exception") it.result("Custom 500 page")
                    }
                    routes.error(555) { it.result("Custom 555 page") }
                    routes.error(500, "html") { if (it.path() == "/html") it.result("HTML error page") }
                }
                client.get("/unmapped").bodyAsText() shouldBe "Custom 404 page"
                client.get("/exception").bodyAsText() shouldBe "Custom 500 page"
                client.get("/555").bodyAsText() shouldBe "Custom 555 page"
                client.get("/html") { header("Accept", "text/html") }.bodyAsText() shouldBe "HTML error page"
                client.get("/html") { header("Accept", "application/json") }.bodyAsText() shouldBe "Error!"
            }
        }

        test("redirect in before handler skips endpoint but runs after handlers") {
            val log = CopyOnWriteArrayList<String>()
            testKalin {
                app {
                    routes.before("/old") { it.redirect("/new") }
                    routes.get("/old") { log.add("old endpoint") }
                    routes.after { log.add("after") }
                }
                client.get("/old").let {
                    it.code shouldBe 302
                    it.header("Location") shouldBe "/new"
                }
                log shouldBe listOf("after")
            }
        }

        test("request logger runs after the response is written") {
            val logged = CopyOnWriteArrayList<String>()
            testKalin {
                app {
                    requestLogger.http { ctx, ms ->
                        logged.add("${ctx.method()} ${ctx.path()} ${ctx.statusCode()} ${ms >= 0}")
                    }
                    routes.get("/x") { it.result("x") }
                }
                client.get("/x")
                client.get("/missing")
                // the logger runs after the response was sent, so wait for it
                val deadline = System.currentTimeMillis() + 5000
                while (logged.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(5)
            }
            logged shouldBe listOf("GET /x 200 true", "GET /missing 404 true")
        }

        test("lifecycle events fire in order") {
            val events = CopyOnWriteArrayList<String>()
            val app =
                net.tegulis.kalin.Kalin.create { config ->
                    config.startup.showBanner = false
                    config.server.port = 0
                    config.events.serverStarting { events.add("starting") }
                    config.events.serverStarted { events.add("started") }
                    config.events.serverStopping { events.add("stopping") }
                    config.events.serverStopped { events.add("stopped") }
                    config.events.handlerAdded { events.add("added ${it.path}") }
                    config.routes.get("/a") {}
                }
            app.start().stop()
            events shouldBe listOf("starting", "added /a", "started", "stopping", "stopped")
        }

        test("routes cannot be added after creation") {
            val app =
                net.tegulis.kalin.Kalin.create {
                    it.server.port = 0
                    it.startup.showBanner = false
                }
            app.start()
            try {
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    @OptIn(net.tegulis.kalin.KalinInternalApi::class) app.unsafeConfig().routes.get("/late") {}
                }
            } finally {
                app.stop()
            }
        }
    })

enum class MyRole : RouteRole {
    ROLE_ONE,
    ROLE_TWO,
    ROLE_THREE,
}

class TypedException : Exception() {
    fun proof() = "I'm so typed"
}

class UserController : net.tegulis.kalin.apibuilder.CrudHandler {
    override fun getAll(ctx: Context) {
        ctx.result("All my users")
    }

    override fun getOne(ctx: Context, resourceId: String) {
        ctx.result("My single user: $resourceId")
    }

    override fun create(ctx: Context) {
        ctx.status(201)
    }

    override fun update(ctx: Context, resourceId: String) {
        ctx.status(204)
    }

    override fun delete(ctx: Context, resourceId: String) {
        ctx.status(204)
    }
}
