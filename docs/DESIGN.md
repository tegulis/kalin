# Kalin design document

> This is the original design document. Section 0 records how it was implemented and the decisions made on the way;
> the rest is kept as written. User documentation is in the [README](../README.md).

## 0. Implementation status (0.2.0)

**Coordinates and packages.** Group `net.tegulis.kalin`; artifacts `kalin` (core, Gradle module `lib`),
`kalin-json`, `kalin-testtools`, `kalin-rendering-jte`, `kalin-inject`, `kalin-bundle`. Packages are
`net.tegulis.kalin.*` instead of `io.kalin.*` used in the examples below. The standalone static file server
`kalin-server` is the Gradle module `app`.

**Milestones.** M1, M2 and the API part of M3 are implemented: both engines, NIO and native epoll transports, the
§6.1 API, SSE, JSON, templates, test tools, DI, async/suspend handlers, `NonBlockingHandler`, multipart uploads,
ranges (`writeSeekableStream`, static files), `writeJsonStream` and `cookieStore()`. Not implemented: the TFB
Fortunes/database tests (the DB driver is an open decision, §11) and the actual TFB submission; io_uring and the
other backlog items.

**Decisions taken during implementation**

| Topic | Decision | Reason |
|---|---|---|
| LOOM engine model | One virtual thread per connection with blocking channel I/O, instead of an own selector for idle connections | The JDK already parks blocked virtual threads on its own epoll poller; an extra selector adds a register/cancel round trip per request. Idle connections cost one parked virtual thread and an 8 KB buffer. |
| Timeouts | One hashed timer wheel per engine (LOOM/memory: a timer thread; event loops: advanced by each loop) with lazily re-bucketed deadlines | A deadline change is one volatile write; no queue operations on the hot path. The wheel also keeps a coarse clock for the hot path (clock reads are expensive with some clock sources). |
| Default engine | `LOOM` | Benchmarks ([docs/benchmarks.md](benchmarks.md)): fastest for ordinary (blocking-allowed) handlers and pipelining; `EVENT_LOOP` wins for non-blocking handlers and is the choice for many idle connections. |
| Default transport | `AUTO` = EPOLL for `EVENT_LOOP` on Linux when native access is enabled, else NIO | As specified. Without `--enable-native-access` Kalin falls back to NIO and logs one warning. |
| Epoll accept | One listening socket with round-robin hand-off by default; `config.server.reusePort = true` gives every loop its own `SO_REUSEPORT` socket | Round-robin balances better with few connections per loop. |
| EVENT_LOOP handlers on virtual threads | The handler thread writes the response directly to the socket and hands completion back without waking the loop when no input is waiting | Removes two thread hops per request (920k vs 709k req/s). |
| Request bodies in EVENT_LOOP | Streamed to virtual-thread handlers through a bounded pipe (reading pauses when it is full); fully buffered (≤ 1 MB) for inline handlers | Backpressure for uploads, no blocking on the loop. |
| `HEAD` | Runs the `GET` handler and sends its headers without the body | RFC 9110 asks for the same headers as `GET`; Javalin answers `200` without running the handler. |
| Suspend handlers | `suspendHandler { ctx -> }` returns a `Handler` | `get(path) suspend { }` is not valid Kotlin; overloads of `get` for suspend lambdas would be ambiguous. |
| JSON | `JsonMapper` takes `KType` (from `typeOf<T>()`) besides `java.lang.reflect.Type`; `Context` is an abstract class so `json(value)`, `bodyAsClass<T>()` are reified members | Generic types such as `List<User>` work with kotlinx.serialization without reflection, and Javalin-style calls compile without extra imports. |
| Test tools REAL mode | JDK `HttpClient`, falling back to raw sockets for headers it refuses (`Host`, `Connection`, ...) and malformed URIs | |
| jte `.kte` hot reload | Needs `gg.jte:jte-kotlin` and a `kotlin-compiler-embeddable` matching the project's Kotlin version | jte-kotlin 3.2.4 bundles Kotlin 2.2, which cannot read Kotlin 2.4 stdlib metadata. |
| kotlin-inject spike (M0) | kotlin-inject 0.9.0 with KSP 2.3.12 works with Kotlin 2.4.20 and context parameters in the same module | Metro was not needed. |
| Formatting and lint | ktfmt (kotlinlang style) from the project template; no ktlint/detekt | |

**Targets (§2)**

| Target | Result |
|---|---|
| Plaintext and JSON faster than netty, jetty, javalin | Met on local runs: 8.2M / 1.25M req/s vs Netty 2.2M / 1.04M (see benchmarks) |
| p99 not worse than Netty | Met for JSON (0.63 ms vs 0.83 ms) |
| Startup < 100 ms | LOOM ~55 ms, EVENT_LOOP/NIO ~70 ms; EVENT_LOOP/EPOLL ~110 ms because of FFM stub generation (started in the background by `Kalin.create`) |
| Idle heap < 15 MB with one route | 5.5 to 7.3 MB |
| Handlers on virtual threads by default | Yes |
| 50k open SSE clients with low memory (EVENT_LOOP) | Met: 50,000 streams in 243 MB heap with 19 platform threads |
| `kalin-core` jar < 400 KB | Not met: ~670 KB. Most of it is Kotlin metadata and bytecode of the full §6.1 API (61 response exception classes alone are ~110 KB uncompressed). |
| Other jars < 50 KB | `kalin-json` 20 KB, `kalin-rendering-jte` 8 KB, `kalin-inject` 6 KB; `kalin-testtools` ~70 KB (not met) |

**Testing.** ~170 tests: parser/codec unit tests with a 200k-input fuzz loop, protocol tests (pipelining, smuggling,
`100-continue`, timeouts/slowloris, header injection), the ported Javalin behaviors (routing, lifecycle, context,
exceptions, validation, static files, SSE, async, plugins), an engine/transport/execution matrix, a concurrent
stress test, and module tests. `./gradlew test -Pkalin.testMode=REAL -Pkalin.engine=... -Pkalin.transport=...`
reruns the core suite over real sockets for each engine and transport (also in CI).

---

A tiny, 100% Kotlin web framework with a Javalin-compatible API and its own HTTP/1.1 server.
It uses no Jetty, Netty or Servlet API and is faster than Javalin, Jetty and Netty on TechEmpower.

> "Kalin" is a working name. Rename before the first release.

```kotlin
import io.kalin.Kalin

fun main() {
    Kalin.create { config ->
        config.routes.get("/") { ctx -> ctx.result("Hello World") }
        config.routes.sse("/events") { client -> client.sendEvent("hello", "world") }
    }.start(7070)
}
```

---

## 1. Decisions

| Topic | Decision |
|---|---|
| Language | 100% Kotlin. Native calls use the JDK FFM API from Kotlin code. |
| Kotlin | 2.4+ (latest stable), `languageVersion = 2.4`, `jvmTarget = 25`. Use modern features, especially context parameters (§9.1). |
| JDK | 25 LTS |
| Platforms | Linux x86-64/arm64 (primary, native epoll). Windows x86-64 (JDK NIO fallback). Nothing else. |
| Protocol | HTTP/1.1 only (cleartext). |
| Out of scope | HTTP/2, HTTP/3, TLS, Servlet API. Use a reverse proxy for TLS/HTTP2/HTTP3. |
| **MUST NOT** | WebSocket. No API, no stubs, no upgrade handling. |
| In MVP | SSE |
| Engines | Two engines, chosen by config. The default is picked later from benchmark data. |
| JSON | kotlinx.serialization (approved 3rd-party, pure Kotlin) in module `kalin-json` |
| Templates | No own engine. Core has the `FileRenderer` hook (like Javalin). Optional adapter module `kalin-rendering-jte`. |
| Dependency injection | Compile-time DI with kotlin-inject (KSP2, no reflection). Supported through the optional `kalin-inject` module; used by examples and the TFB app (§5.5). |
| Testing | `kalin-testtools`: Ktor-style `testKalin { }` (in-memory or real server) plus Javalin-style `KalinTest.test(app) { server, client -> }` |
| Dependencies | `kalin-core`: Kotlin stdlib + JDK only. Any other runtime dependency must be pure Kotlin, justified, and approved by the project owner. Approved list in §5.4. |
| Benchmark | TechEmpower Framework Benchmarks (TFB) |
| Project | Open source, one developer with AI help. License: TBD (see §11). |

---

## 2. Goals

| Goal | Target |
|---|---|
| Throughput | Higher than the `netty`, `jetty` and `javalin` TFB entries on the same hardware, for plaintext and JSON (M2), then Fortunes and DB tests (M3) |
| Latency | p99 not worse than Netty at the same load |
| API | Javalin 7 API shape. Migration = change `io.javalin` imports to `io.kalin` (WebSocket and servlet parts excluded). |
| Jar size | `kalin-core` < 400 KB; `kalin-json`, `kalin-rendering-jte`, `kalin-testtools` < 50 KB each (without their dependencies) |
| Startup | < 100 ms to first request |
| Idle memory | < 15 MB heap with one route |
| Threads | Request handlers run on virtual threads by default |

### Non-goals

- HTTP/2, HTTP/3, TLS, WebSocket (MUST NOT), Servlet API (`ctx.req()`, `ctx.res()`), Jetty config.
- macOS, Android, GraalVM native image (may work, not supported).
- Brotli and Zstd compression. Gzip and Deflate only.
- An own template engine. Users plug any engine via `FileRenderer`; we ship only the jte adapter.
- Sessions (backlog).

### What we lose without HTTP/2 and HTTP/3

- **Browsers:** browsers speak HTTP/2 only over TLS. TLS is out of scope, so production setups put a reverse proxy (Caddy, nginx, HAProxy) in front. The proxy speaks HTTP/2 and HTTP/3 to browsers and keep-alive HTTP/1.1 to Kalin. Browsers lose almost nothing.
- **SSE without a proxy:** over HTTP/1.1, browsers allow only 6 connections per browser and domain, across all tabs. Each open SSE stream uses one. With HTTP/2 the limit is the negotiated stream count, 100 by default. Mitigation: use HTTP/2 at the proxy, or share one `EventSource` per origin across tabs. The docs must explain this.
- **Other:** no gRPC, no h2c between services, no multiplexing for direct clients. TFB uses HTTP/1.1, so the benchmark goals are not affected.

---

## 3. Background research (summary)

### 3.1 Javalin

- Javalin 7: Java 17+, Jetty 12. Routes go in `config.routes` inside `Javalin.create { }` and cannot be added after `start()`.
- Handlers: `before`, `beforeMatched`, endpoints (`get/post/query/put/patch/delete/head/options`, custom via `addHttpHandler`), `afterMatched`, `after`, `exception`, `error`.
- Paths: `{name}` (no slash), `<name>` (slash allowed), `*` wildcard.
- Lifecycle: `before` → `beforeMatched` → endpoint → `afterMatched` → `error` → `after`; `exception` when a handler throws; request logger after the response is written.
- `ctx.future()` / `ctx.async()` for async work. `config.concurrency.useVirtualThreads` switches Jetty to a virtual-thread executor.

### 3.2 Typical usage (Baeldung)

Create app → `get("/users")`, `get("/users/{id}")`, `post("/users")` → handlers in controller objects → `ctx.json(obj)`, `ctx.bodyAsClass<User>()`, `ctx.pathParam("id")`. This must work after an import change (the model class needs `@Serializable` with the default JSON module).

### 3.3 `sun.net.httpserver.ServerImpl` (JDK)

- One `Selector` on one platform thread. Accept → non-blocking + `OP_READ`. When readable: cancel key, switch channel to blocking, run an `Exchange` on the executor (default executor = the dispatcher thread itself, so serial).
- `Exchange`: parse request line and headers, validate, choose fixed-length or chunked body, longest-prefix context lookup, `100 Continue`, filter chain → handler.
- After the response: unread body → close; buffered bytes (pipelining) → next exchange now; else back to non-blocking and re-register as idle.
- States `NEWLY_ACCEPTED`, `REQUEST`, `RESPONSE`, `IDLE` in synchronized sets; two `java.util.Timer`s for idle and request/response timeouts; limits via system properties.
- Keep its security checks: reject `Content-Length` + `Transfer-Encoding`, duplicate or negative `Content-Length`, invalid header names (400); unknown transfer encodings (501).
- Fix its weak points: serial default executor, `select(1000)` polling, global locks, timer threads with full scans, `java.net.URI` + many `String`s per request, single selector, busy-wait `stop()`.

> License note: `ServerImpl` is GPLv2 + Classpath Exception. Study the design only. Do not copy code.

### 3.4 Why native epoll

The TFB `netty` entry uses Netty's native epoll transport. JDK NIO also uses epoll on Linux, but adds selected-key sets, locks and extra copies. To beat Netty we call epoll and related syscalls directly through FFM (`java.lang.foreign`), with off-heap buffers.

---

## 4. Architecture

```
┌──────────────────────────── io.kalin (framework) ────────────────────────────┐
│ Kalin, KalinConfig, RoutesConfig, Router, Context, Validators, SSE,          │
│ exception/error mappers, plugins, static files, JsonMapper, FileRenderer     │
└─────────────────────────────────┬────────────────────────────────────────────┘
                                  │ Exchange API
┌─────────────────────────────────┴────── io.kalin.engine ─────────────────────┐
│  Engine.LOOM                         │  Engine.EVENT_LOOP                     │
│  selector for accept/idle,           │  N event loops, non-blocking           │
│  virtual thread per exchange,        │  state machine per connection          │
│  blocking I/O on virtual threads     │                                        │
├──────────────────────────────────────┼────────────────────────────────────────┤
│  Transport.NIO (JDK)                 │  Transport.EPOLL (Linux, FFM)          │
│                                      │  Transport.NIO (Windows fallback)      │
├──────────────────────────────────────┴────────────────────────────────────────┤
│ Shared: HttpParser, Headers, body codecs (fixed/chunked), ResponseWriter,      │
│ TimerWheel, Limits, Date cache, buffer pools                                   │
└────────────────────────────────────────────────────────────────────────────────┘
```

```kotlin
config.server.engine = Engine.LOOM          // or Engine.EVENT_LOOP
config.server.transport = Transport.AUTO    // AUTO = EPOLL on Linux, NIO elsewhere; or NIO / EPOLL
```

Rule: `Engine.LOOM` supports only `Transport.NIO`. A virtual thread that blocks inside a native FFM call pins its carrier thread, so blocking epoll/read calls would kill scalability. Native epoll is used only by the event-loop engine, where nothing blocks.

### 4.1 Engine.LOOM (M1)

1. One platform thread runs the selector: accept, idle keep-alive connections, register queue, `wakeup()`. No polling timeout.
2. Readable → deregister → run the exchange on a new virtual thread. The channel is blocking; virtual threads unmount while blocked.
3. Parse → dispatch → write → drain leftover body (up to `drainLimit`, else close).
4. Pipelining: if bytes are buffered, continue on the same virtual thread; else return the connection to the selector.
5. One timer wheel on the selector thread: idle, header-read, body-read, write deadlines. A timeout closes the channel; the blocked virtual thread gets an exception and ends.
6. `maxConnections`: extra sockets are closed at once, or get a fast `503` (configurable).

### 4.2 Engine.EVENT_LOOP (M2)

- N event loops (default = CPU cores). Linux: one listening socket per loop with `SO_REUSEPORT`, so the kernel balances accepts. Windows/NIO: one acceptor, round-robin hand-off.
- Each connection is a state machine: `READ_HEAD → READ_BODY → HANDLE → WRITE → IDLE | CLOSE`, owned by one loop. No locks on the hot path.
- Incremental parser over off-heap buffers; per-loop buffer pools; `writev` for header + body; `sendfile` for static files (Linux).
- Partial writes register write interest; producers are paused (backpressure).
- **Handler execution** (`config.server.handlerExecution`):
  - `VIRTUAL_THREAD` (default): handler runs on a virtual thread, result is handed back to the loop. Blocking code is safe.
  - `EVENT_LOOP`: handler runs directly on the loop thread. Fastest, for non-blocking handlers only (TFB plaintext/JSON). Can also be set per endpoint by implementing `NonBlockingHandler`.
- Async APIs: `ctx.future { }`, `ctx.async(cfg) { }`, and `suspend` handlers started with stdlib coroutines (`startCoroutine` + a `ContinuationInterceptor` that dispatches to virtual threads). No kotlinx.coroutines.

### 4.3 Transport.EPOLL (M2, Linux only)

- FFM downcalls to libc: `epoll_create1`, `epoll_ctl`, `epoll_wait`, `eventfd` (wake-up), `socket`, `bind`, `listen`, `accept4` (`SOCK_NONBLOCK | SOCK_CLOEXEC`), `read`, `write`, `readv`, `writev`, `sendfile`, `setsockopt` (`TCP_NODELAY`, `SO_REUSEPORT`, `TCP_DEFER_ACCEPT`), `shutdown`, `close`.
- `errno` read via `Linker.Option.captureCallState("errno")`. Method handles created once and cached in `static final`-style fields.
- Edge-triggered epoll, batch `epoll_wait` (e.g. 512 events).
- Memory: `Arena`-owned `MemorySegment` buffers per loop; no per-request off-heap allocation.
- Native access needs `--enable-native-access=ALL-UNNAMED` or the jar manifest entry `Enable-Native-Access: ALL-UNNAMED`. Kalin checks at startup and falls back to NIO with one warning if native access is denied.
- io_uring: optional experiment after M2, only if epoll misses the targets.

### 4.4 HTTP/1.1 rules (both engines)

- RFC 9110/9112. `Host` required for 1.1. Obsolete line folding rejected.
- Bodies: `Content-Length` or `Transfer-Encoding: chunked` only; smuggling checks from §3.3.
- Responses: fixed length when known, else chunked. `HEAD` sends headers only. `Connection: close` and HTTP/1.0 keep-alive handled.
- `Expect: 100-continue`: send `100` only when the handler first reads the body.
- Pipelining fully supported (TFB plaintext uses it). Responses are written in request order; small pipelined responses are batched into one write.
- Limits (configurable): request line 8 KB, headers 16 KB, 100 headers, `maxRequestSize` 1 MB in memory.
- `Date` header cached and refreshed once per second; common headers pre-encoded as bytes.

### 4.5 SSE (M1)

- `config.routes.sse(path) { client -> }` with the full Javalin `SseClient` API: `sendEvent`, `sendData`, `sendComment`, `onClose`, `keepAlive`, `close`, `terminated`, `ctx`.
- Response: `text/event-stream`, chunked, `Cache-Control: no-cache`, compression off, flush per event.
- Extras: `ctx.header("Last-Event-ID")` helper `client.lastEventId()`, optional heartbeat `config.sse.heartbeat = 15.seconds` (comment line), `retry:` field support.
- Disconnect detection: while the handler or another thread writes events, the connection also watches for read EOF (selector in LOOM, event loop in EVENT_LOOP), so `onClose` fires fast.
- Target: 50k open SSE clients with low memory (EVENT_LOOP: no thread per idle stream).

---

## 5. Modules

| Module | Contents | Runtime deps |
|---|---|---|
| `kalin-core` | Engines, transports, router, Context, SSE, static files, plugins | Kotlin stdlib |
| `kalin-json` | `KotlinxJsonMapper` (default when on classpath) | kotlinx-serialization-json (approved) |
| `kalin-rendering-jte` | `KalinJte` `FileRenderer` adapter | jte (approved, see §5.4) |
| `kalin-testtools` | `testKalin { }`, `KalinTest.test(app) { }`, test client, in-memory transport | Kotlin stdlib (+ JDK `HttpClient`) |
| `kalin-inject` | `@AppScope`, `KalinComponent`, `KalinRoutes`, `config.install(component)` | kotlin-inject runtime (approved) |
| `kalin-bundle` | core + json | none |
| `benchmarks/tfb` | TFB app and scripts (uses `kalin-rendering-jte` for Fortunes; DB driver TBD) | none |
| `examples/` | hello, rest-users (Baeldung port), static, sse, jte, testing, inject | none |

### 5.1 JSON (`kalin-json`)

- `KotlinxJsonMapper(json: Json = Json { ignoreUnknownKeys = true })`, auto-registered if the module is present.
- `ctx.bodyAsClass<T>()` and `ctx.json<T>(obj)` are `inline reified` and use `serializer<T>()` (no reflection at call time).
- `bodyAsClass(Class<T>)` and `json(obj: Any)` look up `serializer(type)` once per class and cache it in a `ClassValue`.
- Streaming: decode from the request `InputStream`, encode straight into the response buffer.
- Classes must be `@Serializable` (or have a registered contextual serializer). A missing serializer gives a clear error naming the class.
- `JsonMapper` stays an interface, so users can plug in any other mapper.

### 5.2 Templates (`FileRenderer` + `kalin-rendering-jte`)

Kalin has no template engine of its own. Like Javalin, `kalin-core` only defines the hook; engines live in optional adapter modules (Javalin: `javalin-rendering-{engine}`).

**Core hook (`kalin-core`):**

```kotlin
fun interface FileRenderer {
    fun render(filePath: String, model: Map<String, Any?>, ctx: Context): String
}

/** Optional: write bytes straight to the response, no String in between. */
interface StreamingFileRenderer : FileRenderer {
    fun render(filePath: String, model: Map<String, Any?>, ctx: Context, out: OutputStream)
}
```

`ctx.render(path, model)` uses the streaming variant when the renderer implements it. Any engine can be plugged in by the user in a few lines.

**Adapter (`kalin-rendering-jte`), mirrors `JavalinJte`:**

```kotlin
Kalin.create { config ->
    config.fileRenderer(KalinJte())                                       // precompiled, from classpath (production)
    // config.fileRenderer(KalinJte(KalinJte.directoryTemplateEngine()))  // hot reload from src/main/jte (dev)
    config.routes.get("/") { ctx -> ctx.render("index.kte", mapOf("user" to user)) }
}
```

- `KalinJte(engine: TemplateEngine = TemplateEngine.createPrecompiled(ContentType.Html))`.
- `KalinJte.directoryTemplateEngine(path = "src/main/jte")` for dev hot reload.
- Implements `StreamingFileRenderer` with jte's UTF-8 byte output (pre-encoded static parts) for best speed.
- Supports `.jte` and `.kte` templates. Precompile them with the official jte Gradle plugin; docs show the setup.
- Kotlin `.kte` hot reload needs `gg.jte:jte-kotlin` (pulls the Kotlin compiler). It is a dev-only dependency and is documented.
- Typed calls: works with jte's generated `Templates` facade (`jte-models`) as an extra, documented in the example.

### 5.3 Test tools (`kalin-testtools`)

Test the app without boilerplate, like Ktor's `testApplication` and Javalin's `JavalinTest`. Works with any test framework (JUnit 5, kotlin.test, Kotest); no assertion library is bundled.

**Ktor-style:**

```kotlin
class PingTest : FunSpec({
    test("ping responds with plain PONG") {
        testKalin {
            app { module() }                          // receiver = KalinConfig, same block as Kalin.create { }
            val response = client.get("/api/v1/ping")
            response.status shouldBe HttpStatus.OK
            response.contentType()?.withoutParameters() shouldBe ContentType.TEXT_PLAIN
            response.bodyAsText() shouldBe "PONG"
        }
    }
})

fun KalinConfig.module() {
    routes.get("/api/v1/ping") { ctx -> ctx.result("PONG") }
}
```

**Javalin-style (for migrated tests):**

```kotlin
@Test
fun `ping works`() = KalinTest.test(app) { server, client ->
    assertEquals(200, client.get("/api/v1/ping").code)
}
```

**Modes:**

| Mode | How | Use |
|---|---|---|
| `TestMode.IN_MEMORY` (default) | `Transport.MEMORY`: an in-memory pipe instead of a socket. Raw HTTP bytes still go through the real parser, engine and writer. | Fast (no ports), parallel-safe, same behavior as production |
| `TestMode.REAL` | Real engine + transport on a random free port; client = JDK `HttpClient` (HTTP/1.1) | End-to-end, engine/transport matrix, `server.port` / `server.url` |

```kotlin
testKalin(mode = TestMode.REAL, engine = Engine.EVENT_LOOP) { ... }
```

**Test client API:**

- `get/post/put/patch/delete/head/options/query(path) { header(..); queryParam(..); cookie(..); body("text"); json(obj); form("a" to "b") }`, `request(method, path)`.
- `TestResponse`: `status`, `code`, `headers`, `header(name)`, `contentType()`, `cookies`, `bodyAsText()`, `bodyAsBytes()`, `body<T>()` (uses the app's `JsonMapper`).
- SSE: `client.sse("/events") { val events = take(3, timeout = 2.seconds) }` → `List<SseEvent>(event, data, id)`; closing the block disconnects (tests `onClose`).
- Raw: `client.raw("GET / HTTP/1.1\r\nHost: x\r\n\r\n")` for protocol edge cases.
- Each `testKalin` block starts and stops its own app; lifecycle events fire as in production. Default timeout per request 10 s (configurable).

Kalin's own test suite is written with `kalin-testtools`, so the tools are tested by daily use.

### 5.4 Approved third-party dependencies

| Dependency | Module | Kind | Reason |
|---|---|---|---|
| kotlinx-serialization-json | `kalin-json` | Runtime, pure Kotlin | Default JSON, no reflection, fast |
| gg.jte:jte | `kalin-rendering-jte` | Runtime, Java (owner-approved exception, optional module only) | Fast precompiled templates; TFB Fortunes |
| me.tatarka.inject:kotlin-inject-runtime | `kalin-inject`, examples, TFB app | Runtime, pure Kotlin | Compile-time DI |
| me.tatarka.inject:kotlin-inject-compiler-ksp + KSP2 | `kalin-inject` tests, examples, TFB app | Build time only | Generates the DI code |
| JUnit 5, kotlin-test, Kotest | tests only | Test | Testing |
| TFB DB driver | `benchmarks/tfb` | Benchmark only | TBD (§11) |

Everything else needs owner approval and an entry here; CI enforces the list.

### 5.5 Dependency injection (`kalin-inject`)

kotlin-inject generates the whole object graph at build time with KSP2: plain constructor calls, no reflection, no runtime scanning. That fits the startup and size goals.

**Split of roles:**

| What | Tool |
|---|---|
| App-scoped objects (repositories, services, controllers, clients, config) | kotlin-inject, `@AppScope` |
| Request-scoped values (`Context`, current user, transaction) | Context parameters (§9.1), no per-request component |

This keeps the hot path free of DI: the graph is built once at startup, and handlers just use the objects they were given.

**Module API:**

```kotlin
@Scope annotation class AppScope

fun interface KalinRoutes { fun register(routes: RoutesConfig) }

interface KalinComponent {
    val routes: Set<KalinRoutes>              // filled with @IntoSet
    val plugins: Set<Plugin> get() = emptySet()
    val jsonMapper: JsonMapper? get() = null
    val fileRenderer: FileRenderer? get() = null
}

fun KalinConfig.install(component: KalinComponent)   // registers routes, plugins, mappers
```

**App example:**

```kotlin
@AppScope @Inject
class UserRepository(private val db: DataSource) { /* ... */ }

@Inject
class UserRoutes(private val repo: UserRepository) : KalinRoutes {
    override fun register(routes: RoutesConfig) {
        routes.get("/users/{id}") { ctx ->
            ctx.json(repo.find(ctx.pathParam("id").toLong()) ?: throw NotFoundResponse())
        }
    }
}

@AppScope @Component
abstract class AppComponent(@get:Provides val db: DataSource) : KalinComponent {
    @Provides @IntoSet protected fun users(r: UserRoutes): KalinRoutes = r
}

fun main() {
    val app = AppComponent::class.create(dataSource())
    Kalin.create { config -> config.install(app) }.start(7070)
}
```

**Testing:** swap the component, using kotlin-inject component inheritance to provide fakes:

```kotlin
@AppScope @Component
abstract class TestAppComponent(@get:Provides val db: DataSource = InMemoryDataSource()) : AppComponent(db)

testKalin {
    app { install(TestAppComponent::class.create()) }
    client.get("/users/1").status shouldBe HttpStatus.OK
}
```

**Rules:**
- Constructor injection only (`@Inject` classes). Kotlin constructors cannot have context parameters, so DI and context parameters do not overlap.
- No `Lazy<T>` or providers on the request path; resolve everything at startup.
- Use qualifiers, not type aliases, to tell bindings apart (type-alias keys are planned for removal in kotlin-inject).
- Set `dumpGraph` in debug builds of the examples, so graph errors are easy to read.

**Risk:** kotlin-inject 0.9.0 is built against Kotlin 2.2.20 and needs KSP2. M0 includes a spike to confirm it works with Kotlin 2.4 and with context parameters in the same module. Fallback: Metro (compiler-plugin DI with similar annotations).

---

## 6. Public API (Javalin compatibility)

### 6.1 M1

| Area | API |
|---|---|
| App | `Kalin.create { }`, `Kalin.start { }`, `start()`, `start(port)`, `start(host, port)`, `stop()`, `port()` |
| Routes | `config.routes.get/post/query/put/patch/delete/head/options`, `addHttpHandler`, `HandlerType.findOrCreate` |
| Filters | `before`, `beforeMatched`, `after`, `afterMatched` |
| Paths | `{param}`, `<param>`, `*`, `ignoreTrailingSlashes`, `treatMultipleSlashesAsSingleSlash`, `caseInsensitiveRoutes`, `contextPath` |
| Groups | `apiBuilder { path() { } }`, roles on `path()`, `crud(path, CrudHandler)` |
| Roles | `RouteRole`, `ctx.routeRoles()` |
| Errors | `exception`, `error(status[, contentType])`, `HttpResponseException` + default responses (400…504), JSON/plain negotiation |
| Context (request) | `body`, `bodyAsBytes`, `bodyAsClass`, `bodyStreamAsClass`, `bodyValidator`, `bodyInputStream`, `pathParam(Map)`, `queryParam(s/Map)`, `formParam(s/Map)`, `header(Map)`, `cookie(Map)`, `attribute(Map)`, `attributeOrCompute`, `basicAuthCredentials`, `method`, `path`, `url`, `fullUrl`, `queryString`, `host`, `ip`, `port`, `protocol`, `scheme`, `contentType`, `contentLength`, `userAgent`, `contextPath`, `endpoint()`, `endpoints()` |
| Context (response) | `result(String/ByteArray/InputStream)`, `json`, `jsonStream`, `html`, `render`, `status`, `header`, `removeHeader`, `contentType`, `redirect`, `cookie`, `removeCookie`, `skipRemainingHandlers` |
| Validation | `*AsClass<T>()`, `check`, `allowNullable`, `getOrDefault`, `errors()`, `collectErrors()`, `config.validation.register` |
| SSE | `config.routes.sse`, `SseClient` (see §4.5) |
| Concurrency | `ctx.async { }` (virtual thread) |
| Config | `config.http` (`maxRequestSize`, `defaultContentType`, `prefer405over404`, `generateEtags`, `strictContentTypes`, `methodOverride`, `responseBufferSize`, `compressionStrategy` = Gzip/Deflate/NONE) |
| Config | `config.server` (replaces `config.jetty`): `host`, `port`, `backlog`, `engine`, `transport`, `eventLoops`, `handlerExecution`, `maxConnections`, `idleTimeout`, `requestTimeout`, header limits |
| Config | `config.router`, `config.contextResolver` (+ `X-Forwarded-*` / `Forwarded` support for proxies), `config.requestLogger.http`, `config.events.*`, `config.appData`, `config.jsonMapper`, `config.fileRenderer`, `config.registerPlugin`, `config.startup.showBanner` |
| Static | `config.staticFiles.add(...)`, `hostedPath`, `headers`, `mimeTypes`, `skipFileFunction`, `roles`, `config.spaRoot.addFile/addHandler` |
| Plugins | `Plugin`; bundled: `enableCors`, `enableRouteOverview`, `enableBasicAuth`, `enableGlobalHeaders`, `enableDevLogging`, `enableRedirectToLowercasePaths`, `RateLimitPlugin` |

### 6.2 M2

`ctx.future { }`, `ctx.async(cfg) { }` with timeout, `config.http.asyncTimeout`, `SuspendHandler` (`get("/x") suspend { ctx -> }`), `CompletableFuture<T>.await()`, `NonBlockingHandler`.

### 6.3 M3

Multipart (`uploadedFile(s)`, `uploadedFileMap`, `isMultipart*`), `writeSeekableStream` (Range), `writeJsonStream`, `cookieStore()`.

### 6.4 Differences from Javalin

The complete, current list is in [differences-javalin.md](differences-javalin.md) (and [differences-ktor.md](differences-ktor.md) for Ktor users). Summary:

- No WebSocket API (`ws*`, `wsException`, `requestLogger.ws`), by design. It will not be added.
- `config.jetty.*` → `config.server.*`. No servlet objects, no `ctx.req()` / `ctx.res()`; `ctx.exchange()` gives the raw engine exchange.
- `useVirtualThreads` is always effectively on for handlers.
- Default JSON is kotlinx.serialization, not Jackson.
- No sessions, no SSL redirect plugin, no Brotli/Zstd.

---

## 7. Milestones (one developer + AI)

### M0: Setup (1 week)

- Gradle multi-module, Kotlin 2.4+, JDK 25 toolchain, `explicitApi()`, `-Xreturn-value-checker=check`, `-Werror`, ktlint/detekt, CI on Ubuntu + Windows.
- Dependency guard: CI fails if `kalin-core` runtime classpath has anything but `kotlin-stdlib`, or if any module adds an unapproved dependency (allow-list file).
- Spike: kotlin-inject 0.9.x + KSP2 with Kotlin 2.4 and context parameters in one module. If it fails, decide on Metro before M1.
- Local TFB setup (Docker) running the `netty`, `jetty` and `javalin` entries to get baseline numbers on our hardware.

### M1: MVP with LOOM engine, Javalin core, SSE, JSON (8 to 10 weeks)

| # | Work item | Done when |
|---|---|---|
| 1 | Selector loop + virtual-thread exchanges, keep-alive, pipelining | `wrk -c 1000` and pipelined plaintext run with no errors |
| 2 | Byte-level parser, limits, smuggling checks | Malformed-input corpus + 1M-input fuzz test, no crash |
| 3 | Body codecs, drain, `100-continue` | 1 GB upload/download, flat heap |
| 4 | Timer wheel; graceful `stop()` | Slowloris closed in time; no dropped in-flight requests |
| 5 | Router, handler pipeline, lifecycle order | Ported Javalin routing/lifecycle tests green |
| 6 | Context, cookies, forms, validators, roles, `apiBuilder`, `crud`; context-parameter helpers (`io.kalin.ktx`, §9.1) | API test per method in §6.1 and per `ktx` helper |
| 7 | Exceptions, errors, default responses | Same status/body as Javalin |
| 8 | SSE | Ported Javalin SSE tests + disconnect test green |
| 9 | `kalin-json` | Baeldung example runs with imports changed + `@Serializable` |
| 10 | Static files, SPA, ETag, gzip; config, events, logger, plugins | Example apps run |
| 11 | `kalin-testtools`: in-memory transport, `testKalin`, `KalinTest`, test client, SSE client (built early, used by all other items) | Kalin's own suite runs on it in both modes |
| 12 | `FileRenderer` + `StreamingFileRenderer`, `kalin-rendering-jte` | jte example renders precompiled `.kte` and hot-reloads in dev |
| 13 | `kalin-inject` + inject example | Example app wired by a generated component; test swaps in a fake component |
| 14 | First TFB plaintext + JSON run | Numbers published vs baselines |

**Exit:** §6.1 API works, CI green on Linux + Windows, size/startup targets met.

### M2: EVENT_LOOP engine + native epoll (10 to 12 weeks)

| # | Work item | Done when |
|---|---|---|
| 1 | Incremental parser on off-heap buffers | M1 corpus passes |
| 2 | Event-loop state machine, write queue, backpressure | Slow reader does not grow memory |
| 3 | NIO transport for EVENT_LOOP | Full test suite green on Windows |
| 4 | FFM epoll transport, `SO_REUSEPORT`, `writev`, `sendfile` | Full test suite green on Linux with EPOLL |
| 5 | Handler execution modes, `NonBlockingHandler` | Both modes pass the suite |
| 6 | `ctx.future`, `ctx.async(cfg)`, suspend handlers | Ported async tests; 100k suspended requests < 1 GB heap |
| 7 | SSE on event loop | 50k open SSE clients |
| 8 | Profiling and tuning (JFR, async-profiler, allocation budget) | Plaintext and JSON beat `netty`, `jetty`, `javalin` in local TFB |

**Exit:** the suite runs on every engine × transport combination; benchmark report; pick default engine.

### M3: Full TFB + remaining API (4 to 6 weeks)

- Multipart, Range, `writeJsonStream`, `cookieStore()`.
- TFB app for Fortunes (via `kalin-rendering-jte`), single query, multiple queries, updates, cached queries (DB driver choice needs approval, §11).
- **Exit:** Fortunes and DB tests beat the same baselines; submit Kalin to TFB.

### Backlog

io_uring transport, sessions, OpenAPI plugin, metrics hooks, Unix domain sockets (Linux).

---

## 8. Testing

- **Unit:** parser, router, validators, timer wheel, codecs.
- **Integration:** written with `kalin-testtools`: `IN_MEMORY` by default, `REAL` mode (random port, JDK `HttpClient`, raw sockets) for end-to-end and malformed input.
- **Matrix:** every integration test runs on LOOM/NIO, EVENT_LOOP/NIO and EVENT_LOOP/EPOLL (Linux).
- **Compatibility:** relevant Javalin tests ported as the executable spec (routing, lifecycle, context, exceptions, validation, static files, SSE, async).
- **Robustness:** parser fuzzing, slowloris, huge headers, deep pipelining, half-closed sockets, resets, FFM error paths (`EAGAIN`, `EINTR`, `EPIPE`, `ECONNRESET`).
- **Performance:** local TFB (plaintext, JSON, then Fortunes/DB) on every perf-related PR; JMH for parser, router, JSON, jte rendering; allocation-per-request budget checked in CI.
- Coverage: ≥ 85% lines in `engine`, `router`, `testtools`.

---

## 9. Coding rules

- Kotlin only, idiomatic, `explicitApi()`, KDoc on every public symbol.
- Hot path: no reflection, no `kotlin-reflect`, no boxing, no lambdas that allocate, no `String` building for headers.
- Never block an event loop or selector thread. Use lock-free hand-off queues between threads.
- Reuse buffers per connection/loop; budget ≤ 1 small allocation set per request (Context + header view).
- Logging via `System.Logger` (no SLF4J).
- Every Javalin difference is listed in §6.4.

### 9.1 Modern Kotlin features

Target the latest stable Kotlin (2.4+). Feature status below is for Kotlin 2.4.

| Feature | Status | Use in Kalin |
|---|---|---|
| Context parameters | Stable (except explicit context arguments and callable references) | Main tool for implicit dependencies, public API and engine internals (see below) |
| Explicit backing fields | Stable | Public read-only views over mutable state (`val routes: List<Endpoint> field = ArrayList()`) |
| Guard conditions in `when` | Stable | Parser and router state machines |
| Non-local `break` / `continue` | Stable | Parser loops inside `inline` lambdas (buffer scanning) |
| Multi-dollar interpolation | Stable | Raw HTTP and JSON literals in tests (`$$"""{"price": "$5"}"""`) |
| Nested type aliases, data-flow `when` exhaustiveness | Stable | General |
| Unused return value checker | Experimental | Enabled in our build (`-Xreturn-value-checker=check`) to catch ignored results; not part of the public API |
| Name-based destructuring, collection literals, explicit context arguments | Experimental | Not used until stable |
| Context receivers | Removed | Never used |

Rule: no experimental language feature in the public API. Internal use only with a comment and an issue to remove the flag.

#### Context parameters in Kalin

They are resolved at compile time and compiled to normal method parameters, so they cost nothing at runtime. Unlike `ThreadLocal` or `ScopedValue`, they also work across virtual-thread and event-loop hand-offs.

**1. Implicit services for handlers** (small apps or scripts; larger apps use kotlin-inject, §5.5):

```kotlin
context(users: UserRepository)
fun KalinConfig.userRoutes() {
    routes.get("/users/{id}") { ctx ->
        ctx.json(users.find(ctx.pathParam("id").toLong()) ?: throw NotFoundResponse())
    }
}

Kalin.create { config ->
    context(UserRepository(db)) { config.userRoutes() }
}.start(7070)
```

**2. Request-scoped helpers (`io.kalin.ktx`, in `kalin-core`).** Javalin-style `{ ctx -> }` handlers stay the default. Kotlin users can opt in to context-style handlers:

```kotlin
context(ctx: Context) fun pathParam(name: String): String = ctx.pathParam(name)
context(ctx: Context) inline fun <reified T : Any> body(): T = ctx.bodyAsClass<T>()
context(ctx: Context) inline fun <reified T : Any> json(value: T) { ctx.json(value) }

fun handler(block: context(Context) () -> Unit): Handler = Handler { ctx -> context(ctx) { block() } }

routes.post("/users", handler {
    val user = body<User>()
    json(repo.save(user))
})
```

User code can combine contexts, e.g. `context(ctx: Context, tx: Transaction) fun currentUser(): User`.

**3. Test tools.** Inside `testKalin { }` the `TestClient` is also a context value, so test helpers need no parameters:

```kotlin
context(client: TestClient)
fun login(user: String): String = client.post("/login") { form("user" to user) }.header("Set-Cookie")!!
```

**4. Engine internals.** Per-loop resources are passed as context: `context(loop: EventLoop, pool: BufferPool) fun Connection.onReadable()`, so the hot path needs no thread-locals.

**Rules:**
- Do not overload the same name with and without context parameters. Since Kotlin 2.3.20 such calls can be ambiguous and fail to compile.
- Callable references to contextual functions are not stable. Functions users register as `Controller::method` must not have context parameters; use lambdas instead.
- Keep each public contextual function to one or two context parameters, with distinct types, to avoid resolution ambiguity.
- Javalin compatibility comes first: every context-style API is an addition, never a replacement.

---

## 10. Deployment

```
Browser ──HTTP/2 or HTTP/3 + TLS──▶ Reverse proxy (Caddy / nginx / HAProxy) ──HTTP/1.1 keep-alive──▶ Kalin
```

- Run with `java --enable-native-access=ALL-UNNAMED -jar app.jar` (or set the manifest entry) to use epoll.
- Enable `X-Forwarded-*` / `Forwarded` handling in `config.contextResolver` so `ctx.ip()`, `ctx.scheme()`, `ctx.url()` are correct behind the proxy.
- For SSE, enable HTTP/2 at the proxy (see §2 "What we lose").

---

## 11. Open decisions

| Topic | Options | Recommendation |
|---|---|---|
| License | Apache-2.0, MIT | Apache-2.0: same as Javalin and kotlinx.serialization, makes porting Javalin tests simple (keep notices) |
| TFB DB driver | pgjdbc (Java, benchmark-only), own minimal async Postgres client in Kotlin | Decide in M3 |
| Default engine | LOOM or EVENT_LOOP | Decide at end of M2 from data |
| kotlin-inject inside `kalin-core` | Use it to wire engine/router/mappers, or plain constructors | Plain constructors: the internal graph is small, and `kalin-core` stays stdlib-only. Revisit if wiring grows. |
| Default `handlerExecution` for EVENT_LOOP | VIRTUAL_THREAD or EVENT_LOOP | VIRTUAL_THREAD (safe); TFB app opts in per endpoint |

---

## 12. References

- Javalin documentation: https://javalin.io/documentation
- Baeldung, *Creating REST Microservices with Javalin*: https://www.baeldung.com/javalin-rest-microservices
- OpenJDK `ServerImpl.java`: https://github.com/openjdk/jdk/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/ServerImpl.java
- JEP 517 (HTTP/3 client only, no server): https://openjdk.org/jeps/517
- SSE connection limits over HTTP/1.1: https://textslashplain.com/2019/12/04/the-pitfalls-of-eventsource-over-http-1-1/
- jte: https://jte.gg · Javalin rendering modules: https://javalin.io/plugins/rendering · kotlinx.serialization: https://github.com/Kotlin/kotlinx.serialization
- Kotlin context parameters: https://kotlinlang.org/docs/context-parameters.html · What's new in Kotlin 2.4: https://kotlinlang.org/docs/whatsnew24.html
- kotlin-inject: https://github.com/evant/kotlin-inject · Metro: https://github.com/ZacSweers/metro
- Ktor testing (`testApplication`): https://ktor.io/docs/server-testing.html · Javalin test tools: https://javalin.io/tutorials/testing
- TechEmpower benchmarks: https://www.techempower.com/benchmarks/
- RFC 9110, RFC 9112, WHATWG HTML (Server-sent events)