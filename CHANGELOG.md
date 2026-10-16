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
- `kalin-json` (kotlinx.serialization), `kalin-rendering-jte` (jte), `kalin-testtools` (`testKalin { }`,
  `KalinTest`), `kalin-inject` (kotlin-inject), `kalin-bundle`.
- `kalin-server`: standalone static file server (`app` module).
- Examples, TechEmpower-style benchmarks and baseline comparison scripts.
