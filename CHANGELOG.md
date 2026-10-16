# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.2.0]

### Changed

- `useForwardedHeaders()` reads only one proxy header family: `X-Forwarded-*` by default, or the RFC 7239
  `Forwarded` header with `headers = ForwardedHeader.FORWARDED`. Apps behind a proxy that sets `Forwarded` must pass
  that argument. `kalin-server` takes `--proxy-headers=forwarded`.
- The `kalin-server` jar is named `kalin-server-<version>-all.jar`.

### Fixed

- Security: `*` in route patterns did not match U+0085, U+2028 or U+2029, so a request path containing one of them
  skipped `before("*")` filters such as basic authentication while still reaching `{param}` endpoints.
- Security: a client-sent `Forwarded` header took precedence over the `X-Forwarded-*` headers set by the proxy and
  could choose `ctx.ip()`, `ctx.host()` and `ctx.scheme()`.
- Security: a request for `//dir` was redirected to `//dir/`, which browsers read as a link to another host.
- Every published jar and the `kalin-server` zip now include LICENSE and NOTICE.

## [0.1.0]

First release.

Requires JDK 25 or later, and Kotlin 2.4 or later in Kotlin projects.

### Added

- `kalin`: Javalin 7 style API (`Kalin.create`, `config.routes`, handlers and filters, `Context`, validation,
  exception and error mappers, default responses, roles, api builder and `crud`, SSE, static files and SPA roots,
  plugins, lifecycle events, request loggers, app data, cookie store, multipart uploads, ranges, ETags, gzip/deflate).
- Own HTTP/1.1 server: `Engine.LOOM` (virtual thread per connection) and `Engine.EVENT_LOOP` (event loops over JDK NIO
  or native Linux epoll through FFM), keep-alive, pipelining, chunked bodies, `100-continue`, smuggling checks,
  limits, timeouts, graceful shutdown, `maxConnections`.
- `NonBlockingHandler` / `nonBlocking { }`, `ctx.future { }`, `ctx.async { }`, `suspendHandler { }`,
  `CompletableFuture.await()`.
- Context-parameter helpers in `net.tegulis.kalin.ktx`.
- `config.http.methodOverride`: `X-HTTP-Method-Override` is honored on `POST` only, and can be turned off.
- `kalin-json` (kotlinx.serialization), `kalin-rendering-jte` (jte), `kalin-testtools` (`testKalin { }`,
  `KalinTest`), `kalin-inject` (kotlin-inject), `kalin-bundle`.
- `kalin-server`: standalone static file server (`app` module).
- Examples, TechEmpower-style benchmarks and baseline comparison scripts.
- `config.http.htmlErrorMessages` (opt-in HTML default error responses), `MultipartConfig.maxParts`,
  `useForwardedHeaders(mode, trustedProxies)` with `ForwardedIp.RIGHTMOST` / `LEFTMOST`, `ctx.forwardedFor()`,
  `KalinJte.directoryTemplateEngine(classDirectory = ...)`.
- `kalin-server`: `--basic-auth-file <path>` and the `KALIN_BASIC_AUTH` environment variable.

### Defaults

- Default error responses are `text/plain` for HTML clients (no unescaped HTML).
- `useForwardedHeaders()` takes the rightmost (proxy-added) forwarded client IP.
- Multipart uploads are limited by `config.http.maxRequestSize` (1 MB) unless `maxTotalRequestSize` is set, and to
  1000 parts.
- `kalin-server` binds to `127.0.0.1`; pass `-b 0.0.0.0` to listen on all interfaces.
- Strict HTTP/1.1 framing: chunked bodies need CRLF, HTTP/1.1 requests need exactly one `Host`, `*` only for
  `OPTIONS`. `config.server.writeTimeout` limits stalls, not total transfer time.

See [docs/differences-javalin.md](docs/differences-javalin.md) and [docs/differences-ktor.md](docs/differences-ktor.md).
