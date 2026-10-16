# Differences from Ktor

Kalin is not Ktor-compatible: its API follows Javalin (see [differences-javalin.md](differences-javalin.md)). This page
is for Ktor users. It maps common Ktor server concepts to Kalin and lists the differences in behavior that are most
likely to surprise.

## Programming model

| | Ktor | Kalin |
|---|---|---|
| Handlers | `suspend` lambdas on coroutines (`call.respond(...)`) | Plain `{ ctx -> }` lambdas on virtual threads; blocking code is fine |
| Coroutines | kotlinx.coroutines everywhere | Optional: `suspendHandler { ctx -> }` with stdlib coroutines; `CompletableFuture.await()` |
| Setup | `embeddedServer(Netty) { module() }` or `EngineMain` + `application.conf`/YAML | `Kalin.create { config -> }.start(port)`, configured in code only |
| Features | `install(Plugin) { }` per feature | Built in and configured on `config` (`config.http`, `config.staticFiles`, ...); optional `config.bundledPlugins.*` |
| Server | Netty, CIO, Jetty or Tomcat engines | Own HTTP/1.1 server: `Engine.LOOM` or `Engine.EVENT_LOOP` over NIO or native epoll |
| Protocols | HTTP/1.1, HTTP/2, TLS, WebSockets | HTTP/1.1 and SSE only. TLS and HTTP/2 come from a reverse proxy; there is no WebSocket support, by design |

## Mapping

| Ktor | Kalin |
|---|---|
| `routing { get("/users/{id}") { } }` | `config.routes.get("/users/{id}") { ctx -> }` |
| `route("/api") { }` | `config.routes.apiBuilder { path("/api") { } }` |
| `call.parameters["id"]` | `ctx.pathParam("id")` |
| `call.request.queryParameters["q"]` | `ctx.queryParam("q")`, `ctx.queryParamAsClass<Int>("q")` |
| `call.receive<User>()` | `ctx.bodyAsClass<User>()` |
| `call.receiveParameters()`, `receiveMultipart()` | `ctx.formParam(...)`, `ctx.uploadedFile(...)` |
| `call.respond(user)`, `respondText(...)` | `ctx.json(user)`, `ctx.result(...)` |
| `call.respondFile(file)` | `ctx.writeSeekableStream(...)` or static files |
| `install(ContentNegotiation) { json() }` | Add `kalin-json` (kotlinx.serialization is picked up automatically) |
| `install(StatusPages) { exception<T> { } }` | `config.routes.exception(T::class.java) { e, ctx -> }`, `config.routes.error(404) { }` |
| `install(CORS)` | `config.bundledPlugins.enableCors { }` |
| `install(Compression)` | On by default (gzip/deflate for large, compressible responses); `config.http.compressionStrategy` |
| `install(Authentication) { basic { } }` | `config.bundledPlugins.enableBasicAuth(...)`, or roles plus a `beforeMatched` check |
| `install(SSE)` + `sse("/x") { send(...) }` | `config.routes.sse("/x") { client -> client.sendEvent(...) }` |
| `staticFiles(...)`, `staticResources(...)`, `singlePageApplication { }` | `config.staticFiles.add(...)`, `config.spaRoot.addFile(...)` |
| `install(XForwardedHeaders)` / `install(ForwardedHeaders)` | `config.contextResolver.useForwardedHeaders(...)` |
| `install(CallLogging)` | `config.requestLogger.http { ctx, ms -> }` |
| `install(RequestValidation)` | Javalin validators: `ctx.bodyValidator<T>().check(...)` |
| `install(Sessions)`, `install(WebSockets)`, `install(Resources)` | Not available |
| `call.request.origin.remoteHost` | `ctx.ip()` |
| `testApplication { }` | `testKalin { }` (`kalin-testtools`) |
| DI plugin, Koin | `kalin-inject` (kotlin-inject, compile time) |

## Behavior differences

### Routing and requests

- **Route order**: Ktor picks the most specific route, whatever order the routes were declared in. Kalin matches
  routes in the order they were registered, like Javalin.
- **Path syntax**: Kalin has `{param}` (one segment), `<param>` (may contain slashes) and `*`. Ktor's optional
  `{param?}` and tailcard `{param...}` have no direct equivalent; use two routes, or `<param>`.
- **Filters**: Kalin uses `before`, `beforeMatched`, `after` and `afterMatched` handlers instead of route-scoped
  plugins and interceptors. Each handler sees only its own path's parameters.
- **`HEAD`** always runs the `GET` handler and drops the body; Ktor needs the `AutoHeadResponse` plugin for that.
- **Bad percent escapes** in query or form parameters answer `400` when the parameter is read.

### Errors

- **Unmatched routes** answer `404` with a short body: JSON for `Accept: application/json`, plain text otherwise.
- **`HttpResponseException`s** (`NotFoundResponse`, `BadRequestResponse`, ...) are the counterpart of responding with
  a status from `StatusPages`.
- **Default error bodies** are `text/plain` even for browsers, unless `config.http.htmlErrorMessages` is enabled (see
  [differences-javalin.md](differences-javalin.md#errors)).

### Uploads

- **Multipart** is parsed when it is first read, not streamed part by part as with `receiveMultipart()`. The whole
  request is limited by `config.http.maxRequestSize` (1 MB by default) unless `multipart.maxTotalRequestSize` is set,
  and to `maxParts` parts (1000 by default). Larger files are written to temporary files, which are deleted after the
  response.

### Proxies

- **Forwarded headers**: Ktor's `XForwardedHeaders` uses the first (leftmost) proxy entry unless configured
  otherwise. Kalin's `useForwardedHeaders()` uses the rightmost entry and skips `trustedProxies` (addresses or CIDR
  ranges), the counterpart of Ktor's `useLastProxy()` / `skipKnownProxies(...)`. `ForwardedIp.LEFTMOST` gives the
  leftmost entry. Like Ktor's two plugins, Kalin reads either `X-Forwarded-*` (the default) or `Forwarded`
  (`headers = ForwardedHeader.FORWARDED`), never both.

### SSE

- **Lifecycle**: an SSE handler returns immediately unless it calls `client.keepAlive()`. Clients can be stored and
  written to from any thread; `client.onClose { }` fires when the browser disconnects.
- **Heartbeats**: `config.sse.heartbeat` sends comment lines on idle streams.

## Testing

| | Ktor `testApplication` | Kalin `testKalin` |
|---|---|---|
| App setup | `application { module() }` | `app { module() }` (receiver is `KalinConfig`) |
| Client | Ktor `HttpClient`, suspend calls | Blocking `TestClient`: `client.get(path)`, `client.post(path) { json(value) }` |
| Transport | In-process test engine | In-memory pipe through the real HTTP parser and writer, or real sockets with `TestMode.REAL` |
| Bodies | `response.bodyAsText()` (suspend) | `response.bodyAsText()`, `response.body<T>()` with the app's JSON mapper |
| Extras | | `client.sse(path) { take(n) }`, `client.raw("GET / HTTP/1.1\r\n...")` for protocol tests |

Tests can run the whole suite over real sockets and another engine with system properties
(`-Pkalin.testMode=REAL -Pkalin.engine=EVENT_LOOP`).
