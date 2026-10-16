package net.tegulis.kalin.examples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.Serializable
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.apibuilder.crud
import net.tegulis.kalin.apibuilder.delete
import net.tegulis.kalin.apibuilder.get
import net.tegulis.kalin.apibuilder.path
import net.tegulis.kalin.apibuilder.post
import net.tegulis.kalin.async.await
import net.tegulis.kalin.async.suspendHandler
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.config.Key
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.Transport
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.NotFoundResponse
import net.tegulis.kalin.http.UnauthorizedResponse
import net.tegulis.kalin.http.nonBlocking
import net.tegulis.kalin.ktx.handler
import net.tegulis.kalin.ktx.pathParam
import net.tegulis.kalin.ktx.respondJson
import net.tegulis.kalin.plugin.bundled.RateLimitPlugin
import net.tegulis.kalin.rendering.jte.KalinJte
import net.tegulis.kalin.security.RouteRole
import net.tegulis.kalin.sse.SseClient
import net.tegulis.kalin.staticfiles.Location
import net.tegulis.kalin.testtools.testKalin
import net.tegulis.kalin.validation.collectErrors

enum class Role : RouteRole {
    LOGGED_IN,
    ADMIN,
}

@Serializable data class Member(val id: Int, val name: String, val age: Int = 30)

class MyException : Exception()

object MemberController {
    fun getAll(ctx: Context) {
        ctx.result("all")
    }

    fun getOne(ctx: Context) {
        ctx.result(ctx.pathParam("id"))
    }

    fun create(ctx: Context) {
        ctx.status(201)
    }

    fun delete(ctx: Context) {
        ctx.status(204)
    }
}

class MemberRepository {
    fun find(id: Long): Member = Member(id.toInt(), "m$id")
}

context(users: MemberRepository)
fun KalinConfig.memberRoutes() {
    routes.get("/members/{id}", handler { respondJson(users.find(pathParam("id").toLong())) })
}

/** The README snippets, compiled and smoke-tested so the documentation stays correct. */
fun KalinConfig.readmeSnippets() {
    routes.before { ctx -> ctx.header("X-Request-Id", UUID.randomUUID().toString()) }
    routes.get("/users/{id}") { ctx -> ctx.result("User " + ctx.pathParam("id")) }
    routes.get("/files/<path>") { ctx -> ctx.result(ctx.pathParam("path")) }
    routes.post("/users") { ctx -> ctx.status(201) }
    routes.addHttpHandler(HandlerType.findOrCreate("PROPFIND"), "/dav/{res}") { ctx -> ctx.result("dav") }
    routes.after("/users/*") { ctx -> ctx.header("X-After", "1") }
    routes.apiBuilder {
        path("/api/users", listOf(Role.LOGGED_IN)) {
            get(MemberController::getAll)
            post(MemberController::create)
            path("/{id}", listOf(Role.ADMIN)) {
                get(MemberController::getOne)
                delete(MemberController::delete)
            }
        }
        crud("/teams/{team-id}", UserControllerForCrud())
    }
    routes.beforeMatched { ctx ->
        val role: RouteRole? = ctx.queryParam("role")?.let { Role.valueOf(it) }
        if (ctx.routeRoles().isNotEmpty() && role !in ctx.routeRoles()) {
            throw UnauthorizedResponse()
        }
    }
    routes.get("/admin", { ctx -> ctx.result("secret") }, Role.ADMIN)
    routes.get("/validate") { ctx ->
        val page = ctx.queryParamAsClass<Int>("page").getOrDefault(1)
        val from = Instant.ofEpochMilli(0)
        val to =
            ctx.queryParamAsClass<Instant>("to").check({ it.isAfter(from) }, "'to' has to be after 'from'").getOrNull()
        val a = ctx.queryParamAsClass<Int>("a")
        val b = ctx.queryParamAsClass<Int>("b")
        ctx.result("$page $to ${listOf(a, b).collectErrors().size}")
    }
    validation.register(Instant::class.java) { Instant.ofEpochMilli(it.toLong()) }
    routes.get("/notfound") { throw NotFoundResponse("No such thing") }
    routes.exception(IllegalStateException::class.java) { e, ctx -> ctx.status(409).result(e.message!!) }
    routes.exception<MyException> { _, ctx -> ctx.status(400) }
    routes.error(404, "html") { ctx -> ctx.html("<h1>Not here</h1>") }
    routes.post("/members") { ctx ->
        val member = ctx.bodyAsClass<Member>()
        ctx.status(201).json(listOf(member))
    }
    val clients = ConcurrentLinkedQueue<SseClient>()
    sse.heartbeat = 15.seconds
    routes.sse("/events") { client ->
        client.keepAlive()
        client.onClose { clients.remove(client) }
        client.sendEvent("welcome", "hi", id = "1")
        clients.add(client)
    }
    staticFiles.add("/public", Location.CLASSPATH)
    spaRoot.addFile("/app", "/public/index.html")
    routes.get("/future") { ctx -> ctx.future { CompletableFuture.supplyAsync { "f" }.thenAccept { ctx.result(it) } } }
    routes.get("/async") { ctx ->
        ctx.async({ cfg ->
            cfg.timeout = 1000
            cfg.onTimeout { it.status(504) }
        }) {
            ctx.result("slow")
        }
    }
    routes.get("/suspend", suspendHandler { ctx -> ctx.result(CompletableFuture.supplyAsync { "s" }.await()) })
    routes.get("/fast", nonBlocking { ctx -> ctx.result("fast") })
    bundledPlugins.enableCors { cors -> cors.addRule { it.allowHost("example.com", "*.example.org") } }
    bundledPlugins.enableGlobalHeaders { it.xContentTypeOptionsNoSniff() }
    bundledPlugins.enableRouteOverview("/routes")
    registerPlugin(RateLimitPlugin())
    routes.get("/limited") { ctx ->
        ctx.with(RateLimitPlugin::class).requestPerTimeUnit(5, TimeUnit.MINUTES)
        ctx.result("ok")
    }
    server.engine = Engine.LOOM
    server.transport = Transport.AUTO
    server.idleTimeout = 30.seconds
    http.maxRequestSize = 1_000_000
    contextResolver.useForwardedHeaders()
    requestLogger.http { ctx, ms -> check(ms >= 0 || ctx.statusCode() > 0) }
    events.serverStarted {}
    appData(Key<String>("k"), "v")
    context(MemberRepository()) { memberRoutes() }
}

class UserControllerForCrud : net.tegulis.kalin.apibuilder.CrudHandler {
    override fun getAll(ctx: Context) {
        ctx.result("teams")
    }

    override fun getOne(ctx: Context, resourceId: String) {
        ctx.result(resourceId)
    }

    override fun create(ctx: Context) {}

    override fun update(ctx: Context, resourceId: String) {}

    override fun delete(ctx: Context, resourceId: String) {}
}

class ReadmeSnippetsTest :
    FunSpec({
        test("README snippets compile and work") {
            testKalin {
                app { readmeSnippets() }
                client.get("/users/7").bodyAsText() shouldBe "User 7"
                client.get("/files/a/b").bodyAsText() shouldBe "a/b"
                client.get("/api/users") { queryParam("role", "LOGGED_IN") }.bodyAsText() shouldBe "all"
                client.get("/api/users/1").code shouldBe 401
                client.get("/teams/3").bodyAsText() shouldBe "3"
                client.get("/validate?page=2&to=5").bodyAsText() shouldBe "2 1970-01-01T00:00:00.005Z 0"
                client.post("/members") { json(Member(1, "a")) }.code shouldBe 201
                client.get("/future").bodyAsText() shouldBe "f"
                client.get("/async").bodyAsText() shouldBe "slow"
                client.get("/suspend").bodyAsText() shouldBe "s"
                client.get("/members/3").body<Member>().name shouldBe "m3"
                client.get("/missing") { header("Accept", "text/html") }.bodyAsText() shouldBe "<h1>Not here</h1>"
            }
            Kalin.create { it.fileRenderer(KalinJte()) }
        }
    })
