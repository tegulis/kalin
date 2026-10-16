# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - unreleased

First release.

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
- `useForwardedHeaders()` takes the rightmost (proxy-added) forwarded client IP and reads only `X-Forwarded-*`;
  pass `headers = ForwardedHeader.FORWARDED` for the RFC 7239 `Forwarded` header. `kalin-server` takes
  `--proxy-headers=forwarded`.
- Multipart uploads are limited by `config.http.maxRequestSize` (1 MB) unless `maxTotalRequestSize` is set, and to
  1000 parts.
- `kalin-server` binds to `127.0.0.1`; pass `-b 0.0.0.0` to listen on all interfaces.
- Strict HTTP/1.1 framing: chunked bodies need CRLF, HTTP/1.1 requests need exactly one `Host`, `*` only for
  `OPTIONS`. `config.server.writeTimeout` limits stalls, not total transfer time.

See [docs/differences-javalin.md](docs/differences-javalin.md) and [docs/differences-ktor.md](docs/differences-ktor.md).
