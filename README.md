# Kalin

A tiny, 100% Kotlin web framework with a [Javalin](https://javalin.io)-compatible API and its own HTTP/1.1 server.
No Jetty, no Netty, no Servlet API — just the Kotlin standard library and the JDK.

```kotlin
import net.tegulis.kalin.Kalin

fun main() {
    Kalin.create { config ->
        config.routes.get("/") { ctx -> ctx.result("Hello World") }
        config.routes.sse("/events") { client -> client.sendEvent("hello", "world") }
    }.start(7070)
}
```

- **Javalin 7 API**: `config.routes`, handlers, filters, `Context`, validation, exception/error mappers, SSE, static
  files, plugins. Migrating usually means changing `io.javalin` imports to `net.tegulis.kalin`.
- **Own HTTP/1.1 server** with two engines: `LOOM` (a virtual thread per connection) and `EVENT_LOOP` (N event loops,
  JDK NIO or native Linux **epoll through the FFM API**). Keep-alive, pipelining, chunked bodies, `100-continue`,
  request smuggling checks, slowloris protection, graceful shutdown.
- **Fast**: on a local TechEmpower-style benchmark Kalin beats Netty, Jetty and Javalin on plaintext and JSON
  ([numbers](docs/benchmarks.md)).
- **Small and self-contained**: `kalin` depends only on `kotlin-stdlib`. Starts in ~50 ms, idles at ~6 MB heap.
- **Kotlin first**: reified `ctx.bodyAsClass<T>()` / `ctx.json(value)`, context-parameter helpers, `suspend` handlers
  on virtual threads, kotlinx.serialization, compile-time DI with kotlin-inject.
- **Batteries for testing**: `testKalin { }` (Ktor style, in-memory or real sockets) and
  `KalinTest.test(app) { server, client -> }` (Javalin style).
- **`kalin-server`**: a standalone static file server (like a minimal nginx/Apache) built on Kalin.

> Kalin is a working name. HTTP/2, HTTP/3 and TLS are out of scope: put a reverse proxy in front (see
> [Deployment](#deployment)). WebSocket is not supported, by design.

## Contents

- [Installation](#installation)
- [Modules](#modules)
- [Routing and handlers](#routing-and-handlers)
- [Context](#context)
- [Validation](#validation)
- [Errors and exceptions](#errors-and-exceptions)
- [JSON](#json)
- [Server-sent events](#server-sent-events)
- [Static files and single page apps](#static-files-and-single-page-apps)
- [Templates](#templates)
- [Async and suspend handlers](#async-and-suspend-handlers)
- [Plugins](#plugins)
- [Configuration](#configuration)
- [Engines and transports](#engines-and-transports)
- [Testing](#testing)
- [Dependency injection](#dependency-injection)
- [Context parameters](#context-parameters)
- [kalin-server: static file server](#kalin-server-static-file-server)
- [Deployment](#deployment)
- [Differences from Javalin](#differences-from-javalin)
- [Building](#building)

## Installation

Requirements: JDK 25+, Kotlin 2.4+ (for the Kotlin-specific APIs).

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.tegulis.kalin:kalin:0.1.0")
    implementation("net.tegulis.kalin:kalin-json:0.1.0")          // kotlinx.serialization JSON (optional)
    testImplementation("net.tegulis.kalin:kalin-testtools:0.1.0")  // testKalin { } (optional)
}
```

`kalin-bundle` combines `kalin` and `kalin-json`. For the native epoll transport, run with
`--enable-native-access=ALL-UNNAMED` (or the jar manifest entry `Enable-Native-Access: ALL-UNNAMED`).

## Modules

| Artifact | Contents | Runtime dependencies |
|---|---|---|
| `kalin` | Engines, transports, router, `Context`, SSE, static files, validation, plugins | `kotlin-stdlib` |
| `kalin-json` | `KotlinxJsonMapper` (registered automatically) | kotlinx-serialization-json |
| `kalin-rendering-jte` | `KalinJte` template renderer | jte |
| `kalin-testtools` | `testKalin { }`, `KalinTest`, test client, in-memory transport | — |
| `kalin-inject` | `KalinComponent`, `KalinRoutes`, `@AppScope`, `config.install(component)` | kotlin-inject runtime |
| `kalin-bundle` | `kalin` + `kalin-json` | — |

A build check (`./gradlew checkDependencies`, part of `check`) enforces the allowed runtime dependencies listed in
[`dependency-allowlist.txt`](dependency-allowlist.txt).

## Routing and handlers

Routes are declared in `config.routes` while the app is created; they cannot be added after `start()`.

```kotlin
val app = Kalin.create { config ->
    config.routes.before { ctx -> ctx.header("X-Request-Id", UUID.randomUUID().toString()) }
    config.routes.get("/users/{id}") { ctx -> ctx.result("User " + ctx.pathParam("id")) }
    config.routes.get("/files/<path>") { ctx -> ctx.result(ctx.pathParam("path")) }   // may contain slashes
    config.routes.post("/users") { ctx -> ctx.status(201) }
    config.routes.addHttpHandler(HandlerType.findOrCreate("PROPFIND"), "/dav/{res}") { ctx -> ctx.result("dav") }
    config.routes.after("/users/*") { ctx -> ctx.header("X-After", "1") }
}.start(7070)
```

- Methods: `get`, `post`, `query`, `put`, `patch`, `delete`, `head`, `options`, custom methods through
  `HandlerType.findOrCreate`.
- Paths: `{param}` (no slash), `<param>` (slashes allowed), `*` wildcard, `/{param}*` matches the path and every sub
  path. `config.router.ignoreTrailingSlashes` (default `true`), `treatMultipleSlashesAsSingleSlash`,
  `caseInsensitiveRoutes`, `contextPath`.
- Endpoints are matched in registration order (a static-path index keeps this fast).
- Lifecycle: `before` → `beforeMatched` → endpoint (or static files, SPA root, 404/405) → `afterMatched` → `error` →
  `after`; `exception` mappers run whenever a handler throws; request loggers run after the response was written.
- `ctx.skipRemainingHandlers()` stops the chain; `ctx.redirect()` in a before handler skips the endpoint.

Grouping with the api builder (Kotlin: `import net.tegulis.kalin.apibuilder.*`, Java:
`import static net.tegulis.kalin.apibuilder.ApiBuilder.*`):

```kotlin
config.routes.apiBuilder {
    path("/users", listOf(Role.LOGGED_IN)) {
        get(UserController::getAll)
        post(UserController::create)
        path("/{id}", listOf(Role.ADMIN)) {
            get(UserController::getOne)
            delete(UserController::delete)
        }
    }
    crud("/teams/{team-id}", TeamController())   // getAll, getOne, create, update, delete
}
```

Access management uses roles and a `beforeMatched` handler:

```kotlin
config.routes.beforeMatched { ctx ->
    if (ctx.routeRoles().isNotEmpty() && currentRole(ctx) !in ctx.routeRoles()) throw UnauthorizedResponse()
}
config.routes.get("/admin", { ctx -> ctx.result("secret") }, Role.ADMIN)
```

## Context

Request: `body()`, `bodyAsBytes()`, `bodyAsClass<T>()`, `bodyStreamAsClass<T>()`, `bodyInputStream()`,
`bodyValidator<T>()`, `pathParam`, `queryParam(s)`, `formParam(s)`, `header`, `headerMap`, `cookie`, `cookieMap`,
`attribute`, `attributeOrCompute`, `basicAuthCredentials`, `uploadedFile(s)`, `method`, `path`, `url`, `fullUrl`,
`queryString`, `host`, `ip`, `port`, `protocol`, `scheme`, `contentType`, `contentLength`, `userAgent`,
`endpoint()`, `endpoints()`, `routeRoles()`, `appData(key)`, `cookieStore()`, `exchange()`.

Response: `result(String | ByteArray | InputStream)`, `json(value)`, `jsonStream(value)`, `writeJsonStream(stream)`,
`html`, `render`, `status`, `header`, `addHeader`, `removeHeader`, `contentType`, `redirect`, `cookie`,
`removeCookie`, `outputStream()`, `writeSeekableStream` (HTTP ranges), `future { }`, `async { }`.

Small responses are sent with `Content-Length` in one write; large or streamed ones use chunked encoding. Responses
are gzip-compressed (`config.http.compressionStrategy`) when they are large enough and of a compressible type.
`config.http.generateEtags = true` adds ETags and answers `If-None-Match` with `304`.

## Validation

```kotlin
val page = ctx.queryParamAsClass<Int>("page").getOrDefault(1)
val to = ctx.queryParamAsClass<Instant>("to").check({ it.isAfter(from) }, "'to' has to be after 'from'").get()
val user = ctx.bodyValidator<User>().check({ it.age >= 18 }, "TOO_YOUNG").get()
val errors = listOf(validatorA, validatorB).collectErrors()
config.validation.register(Instant::class.java) { Instant.ofEpochMilli(it.toLong()) }
```

A failed validation answers `400` with the errors as JSON, for example
`{"page":[{"message":"TYPE_CONVERSION_FAILED","args":{},"value":"x"}]}`.

## Errors and exceptions

```kotlin
config.routes.get("/") { throw NotFoundResponse("No such thing") }      // 404, JSON or text by Accept header
config.routes.exception(IllegalStateException::class.java) { e, ctx -> ctx.status(409).result(e.message!!) }
config.routes.exception<MyException> { e, ctx -> ctx.status(400) }    // reified variant
config.routes.error(404, "html") { ctx -> ctx.html("<h1>Not here</h1>") }
```

`HttpResponseException` subclasses exist for every common status (`BadRequestResponse`, `UnauthorizedResponse`,
`ForbiddenResponse`, `NotFoundResponse`, `ConflictResponse`, `TooManyRequestsResponse`, ...). Uncaught exceptions
answer `500` and are logged.

## JSON

With `kalin-json` on the classpath, `KotlinxJsonMapper` (kotlinx.serialization) is used automatically:

```kotlin
@Serializable data class User(val id: Int, val name: String)

config.routes.post("/users") { ctx ->
    val user = ctx.bodyAsClass<User>()          // reified, no reflection
    ctx.status(201).json(listOf(user))          // the static type is used, so generic lists work
}
```

Values typed as `Any`, `Map<String, Any?>` or `List<Any?>` are converted dynamically. Without `kalin-json`, a
minimal built-in mapper handles maps, lists, strings, numbers and booleans. Any other mapper can be plugged in with
`config.jsonMapper(mapper)` by implementing `JsonMapper`.

## Server-sent events

```kotlin
val clients = ConcurrentLinkedQueue<SseClient>()
config.sse.heartbeat = 15.seconds                       // optional comment line on idle streams
config.routes.sse("/events") { client ->
    client.keepAlive()                                  // keep the stream open after the handler returns
    client.onClose { clients.remove(client) }           // fires when the browser disconnects
    client.sendEvent("welcome", "hi", id = "1")
    clients.add(client)
}
clients.forEach { it.sendEvent("news", News(...)) }     // objects are sent as JSON
```

`SseClient`: `sendEvent`, `sendData`, `sendComment`, `sendRetry`, `lastEventId()`, `keepAlive`, `onClose`, `close`,
`terminated`, `ctx()`. Client disconnects are detected while the stream is idle. With `Engine.EVENT_LOOP`, an idle
kept-alive stream holds no thread.

Browsers allow only 6 HTTP/1.1 connections per origin (across tabs), and each `EventSource` uses one. Serve SSE
through an HTTP/2 reverse proxy, or share one `EventSource` between tabs.

## Static files and single page apps

```kotlin
config.staticFiles.add("/public", Location.CLASSPATH)
config.staticFiles.add { files ->
    files.hostedPath = "/assets"
    files.directory = "/var/www/assets"
    files.location = Location.EXTERNAL
    files.headers = mapOf("Cache-Control" to "max-age=3600")
    files.precompressedFiles = true       // serve app.js.gz when the client accepts gzip
    files.directoryListing = false
}
config.spaRoot.addFile("/", "/public/index.html")      // unknown HTML requests get the SPA page
```

Static files run after endpoint matching (endpoints win). Supported: ETag/Last-Modified conditional requests (`304`),
single byte ranges (`206`/`416`), on-the-fly gzip with an in-memory cache, precompressed `.gz` siblings, zero-copy
`sendfile`, directory redirects (`/dir` → `/dir/`), index files, optional directory listings, hidden-file filtering,
and protection against path traversal and symlinks leaving the directory.

## Templates

Kalin has no template engine; any engine can be plugged in with `config.fileRenderer { path, model, ctx -> ... }`
(or a `StreamingFileRenderer` that writes bytes directly). `kalin-rendering-jte` supports [jte](https://jte.gg):

```kotlin
config.fileRenderer(KalinJte())                                       // precompiled templates (production)
config.fileRenderer(KalinJte(KalinJte.directoryTemplateEngine()))     // hot reload from src/main/jte (development)
config.routes.get("/") { ctx -> ctx.render("index.jte", mapOf("page" to page)) }
```

Precompile templates with the jte Gradle plugin (`id("gg.jte.gradle")`, `jte { generate() }`); see the
`examples` module. Hot reloading `.kte` templates needs `gg.jte:jte-kotlin` and a `kotlin-compiler-embeddable`
matching your Kotlin version.

## Async and suspend handlers

```kotlin
config.routes.get("/future") { ctx -> ctx.future { httpClient.sendAsync(req, ofString()).thenAccept { ctx.result(it.body()) } } }
config.routes.get("/async") { ctx ->
    ctx.async({ cfg -> cfg.timeout = 1000; cfg.onTimeout { it.status(504) } }) { ctx.result(slowCall()) }
}
config.routes.get("/suspend", suspendHandler { ctx -> ctx.result(fetch().await()) })   // stdlib coroutines only
```

Handlers run on virtual threads, so blocking code is fine. `config.http.asyncTimeout` limits `future` results.

## Plugins

```kotlin
config.bundledPlugins.enableCors { cors -> cors.addRule { it.allowHost("example.com", "*.example.org") } }
config.bundledPlugins.enableBasicAuth("user", "password")
config.bundledPlugins.enableGlobalHeaders { it.xContentTypeOptionsNoSniff() }
config.bundledPlugins.enableRouteOverview("/routes")
config.bundledPlugins.enableDevLogging()
config.bundledPlugins.enableRedirectToLowercasePaths()
config.bundledPlugins.enableHttpAllowedMethodsOnRoutes()
config.registerPlugin(RateLimitPlugin())   // ctx.with(RateLimitPlugin::class).requestPerTimeUnit(5, TimeUnit.MINUTES)
```

Write your own by extending `Plugin<CONFIG>` (`onInitialize`, `onStart`, `priority`) or `ContextPlugin` (adds
`ctx.with(MyPlugin::class)`).

## Configuration

```kotlin
Kalin.create { config ->
    config.server.port = 8080                 // config.server replaces Javalin's config.jetty
    config.server.engine = Engine.LOOM        // or Engine.EVENT_LOOP
    config.server.transport = Transport.AUTO  // EPOLL on Linux with native access, else NIO
    config.server.idleTimeout = 30.seconds
    config.server.requestTimeout = 30.seconds // the whole request head must arrive within this time
    config.server.maxConnections = 10_000
    config.http.maxRequestSize = 1_000_000    // body bytes read into memory by ctx.body()
    config.http.defaultContentType = "text/plain"
    config.router.contextPath = "/api"
    config.contextResolver.useForwardedHeaders()   // trust X-Forwarded-* / Forwarded from your proxy
    config.requestLogger.http { ctx, ms -> log.info("${ctx.method()} ${ctx.path()} ${ctx.statusCode()} $ms ms") }
    config.events.serverStarted { log.info("up") }
    config.appData(myKey, myValue)
}
```

Server limits: request line 8 KB (`414`), header section 16 KB and 100 fields (`431`), unread request bodies up to
256 KB are drained to keep connections alive. Logging goes through `System.Logger` (route it to SLF4J with
`slf4j-jdk-platform-logging`).

## Engines and transports

| | `Engine.LOOM` (default) | `Engine.EVENT_LOOP` |
|---|---|---|
| Model | one virtual thread per connection, blocking I/O | N event loops, non-blocking state machines |
| Handlers | on the connection's virtual thread | on a virtual thread (default), or inline on the loop for `NonBlockingHandler`s / `HandlerExecution.EVENT_LOOP` |
| Transports | NIO | NIO (all platforms), EPOLL (Linux, FFM) |
| Best for | typical apps with blocking handlers, pipelining | non-blocking endpoints, very many idle connections (SSE) |

Mark endpoints that never block to run them on the loop thread: `routes.get("/fast", nonBlocking { ctx -> ... })`.
The engine is chosen from benchmark data: `LOOM` is the fastest for ordinary (blocking-allowed) handlers, `EVENT_LOOP`
for non-blocking ones; see [docs/benchmarks.md](docs/benchmarks.md).

## Testing

```kotlin
class PingTest : FunSpec({
    test("ping") {
        testKalin {                                       // in-memory by default; testKalin(mode = TestMode.REAL) for sockets
            app { routes.get("/api/v1/ping") { ctx -> ctx.result("PONG") } }
            val response = client.get("/api/v1/ping")
            response.status shouldBe HttpStatus.OK
            response.contentType()?.withoutParameters() shouldBe ContentType.TEXT_PLAIN
            response.bodyAsText() shouldBe "PONG"
            client.post("/users") { json(User(1, "a")) }.body<User>()
            client.sse("/events") { take(3, timeout = 2.seconds) }
            client.raw("GET / HTTP/1.1\r\nHost: x\r\n\r\n")   // protocol edge cases
        }
    }
})

@Test fun `ping works`() = KalinTest.test(app) { server, client -> assertEquals(200, client.get("/api/v1/ping").code) }
```

In-memory mode sends raw HTTP bytes through the real parser and response writer without sockets. The system
properties `kalin.testMode`, `kalin.engine` and `kalin.transport` switch a whole suite to real sockets and another
engine (`./gradlew test -Pkalin.testMode=REAL -Pkalin.engine=EVENT_LOOP -Pkalin.transport=EPOLL`). Inside
`testKalin { }` the client is also a context parameter, so helpers can be written as
`context(client: TestClient) fun login(user: String) = ...`.

## Dependency injection

`kalin-inject` wires apps with [kotlin-inject](https://github.com/evant/kotlin-inject) (compile time, no reflection):

```kotlin
@AppScope @Inject class UserRepository(private val db: DataSource) { /* ... */ }

@Inject class UserRoutes(private val repo: UserRepository) : KalinRoutes {
    override fun register(routes: RoutesConfig) {
        routes.get("/users/{id}") { ctx -> ctx.json(repo.find(ctx.pathParam("id").toLong()) ?: throw NotFoundResponse()) }
    }
}

@AppScope @Component
abstract class AppComponent(@get:Provides val db: DataSource) : KalinComponent {
    @Provides @IntoSet protected fun users(r: UserRoutes): KalinRoutes = r
}

Kalin.create { config -> config.install(AppComponent::class.create(dataSource())) }.start(7070)
```

## Context parameters

```kotlin
import net.tegulis.kalin.ktx.*

context(users: UserRepository)
fun KalinConfig.userRoutes() {
    routes.get("/users/{id}", handler { respondJson(users.find(pathParam("id").toLong())) })
}

Kalin.create { config -> context(UserRepository(db)) { config.userRoutes() } }
```

## kalin-server: static file server

The `app` module builds `kalin-server`, which serves a directory like a minimal nginx or Apache:

```sh
./gradlew :app:shadowJar
java --enable-native-access=ALL-UNNAMED -jar app/build/libs/app-0.1.0-all.jar -p 8080 /var/www
```

```
Usage: kalin-server [options] [directory]

  -p, --port <port>           Port to listen on (default 8080, 0 = random)
  -b, --bind <host>           Address to bind (default: all interfaces)
      --context-path <path>   Serve below a path prefix, for example /files
      --index <files>         Comma separated index files (default index.html,index.htm)
      --no-listing            Do not list directories without an index file
      --hidden                Serve hidden files (names starting with '.')
      --follow-symlinks       Serve symbolic links that point outside the directory
      --spa <file>            Single page app fallback for unknown browser paths
      --cache <seconds>       Cache-Control max-age for files
      --no-gzip               Disable response compression
      --precompressed         Serve file.gz instead of file when the client accepts gzip
      --cors                  Allow cross-origin requests from any origin
      --basic-auth <user:pw>  Require HTTP basic authentication
  -H, --header <name:value>   Add a response header (repeatable)
      --proxy-headers         Trust X-Forwarded-* and Forwarded headers
      --engine / --transport  LOOM | EVENT_LOOP, AUTO | NIO | EPOLL
  -q, --quiet                 No access log (Common Log Format by default)
      --log-format <f>        text or json
```

`./gradlew :app:packZip` builds a distributable zip.

## Deployment

```
Browser ──HTTP/2 or HTTP/3 + TLS──▶ reverse proxy (Caddy / nginx / HAProxy) ──HTTP/1.1 keep-alive──▶ Kalin
```

Run with `java --enable-native-access=ALL-UNNAMED -jar app.jar` to allow the epoll transport, call
`config.contextResolver.useForwardedHeaders()` so `ctx.ip()`, `ctx.scheme()` and `ctx.url()` reflect the client, and
enable HTTP/2 at the proxy for SSE-heavy apps. `app.stop()` stops accepting connections, closes idle ones, waits up
to `config.server.shutdownTimeout` for in-flight requests and then closes the rest; call it from a shutdown hook.

## Differences from Javalin

- Packages `io.javalin.*` → `net.tegulis.kalin.*`. Default JSON is kotlinx.serialization (`@Serializable`).
- No WebSocket API, no servlet objects (`ctx.req()`, `ctx.res()`), no sessions, no SSL redirect plugin, no Brotli or
  Zstd. `ctx.exchange()` gives the raw engine exchange instead.
- `config.jetty.*` → `config.server.*`; handlers always run on virtual threads.
- `HEAD` requests run the matching `GET` handler and send its headers without the body (Javalin answers `200`
  without running it).
- Suspend handlers are written as `suspendHandler { ctx -> }`.
- Test tools: `testKalin { }` / `KalinTest.test(app) { server, client -> }`; JSON request bodies are set with
  `client.post(path) { json(value) }`.

The design document with background and decisions is in [docs/DESIGN.md](docs/DESIGN.md).

## Building

```sh
./gradlew build                         # all modules, tests (in memory) and the dependency check
./gradlew :lib:test -Pkalin.testMode=REAL -Pkalin.engine=EVENT_LOOP -Pkalin.transport=EPOLL
./gradlew :examples:run -Pexample=RestUsers
./gradlew :benchmarks:installDist       # TechEmpower-style app, see docs/benchmarks.md
./gradlew publishAllPublicationsToStagingRepository   # local staging repository in build/staging-repo
./gradlew :dokkaGenerate                # API documentation in build/dokka/html
```

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
