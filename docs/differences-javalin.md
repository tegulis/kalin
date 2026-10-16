# Differences from Javalin

Kalin follows the Javalin 7 API: most apps migrate by changing `io.javalin` imports to `net.tegulis.kalin`. This page
lists everything that behaves differently. For Ktor, see [differences-ktor.md](differences-ktor.md).

## Not supported

- **WebSocket** (`ws*`, `wsException`, `requestLogger.ws`): left out by design and will not be added. Use SSE
  (`config.routes.sse`) for server push.
- **Servlet API**: there is no `ctx.req()` / `ctx.res()`, `HttpServletRequest` or servlet filters. `ctx.exchange()`
  gives the raw engine exchange instead.
- **Sessions** (`ctx.sessionAttribute`, Jetty session handler).
- **TLS, HTTP/2 and HTTP/3**, and so also the SSL redirect plugin. Put a reverse proxy in front (see the README's
  Deployment section).
- **Brotli and Zstd compression**: only gzip and deflate are available.

## Renamed or replaced

| Javalin | Kalin |
|---|---|
| `io.javalin.*` | `net.tegulis.kalin.*` |
| `config.jetty.*` | `config.server.*` (port, host, engine, transport, timeouts, limits) |
| `config.useVirtualThreads` | always on: handlers run on virtual threads |
| Jackson (`JavalinJackson`) | kotlinx.serialization (`kalin-json`, `@Serializable`), or any `JsonMapper` |
| `JavalinTest.test(app) { server, client -> }` | `KalinTest.test(app) { server, client -> }`, or `testKalin { }` |
| `JavalinJte` | `KalinJte` (`kalin-rendering-jte`) |
| `get(path) { ctx -> }` with Kotlin coroutines | `get(path, suspendHandler { ctx -> })` (stdlib coroutines only) |

`JsonMapper` also takes a `KType` besides `java.lang.reflect.Type`, so `ctx.bodyAsClass<List<User>>()` and
`ctx.json(listOf(user))` keep their generic types without reflection.

## Behavior differences

### Requests and routing

- **`HEAD`** runs the matching `GET` handler and sends its headers without the body, as RFC 9110 asks. Javalin answers
  `200` without running the handler.
- **Bad percent escapes**: query and form parameters with invalid percent escapes (for example `a=%ZZ`) answer
  `400 Bad Request` when they are read. A malformed `filename*` in a multipart upload also answers `400`.
- **Path parameters** are scoped to each handler's own path, as in Javalin: a `before("/users/{id}")` filter does not
  pass `id` on to a static `/users/me` endpoint. `beforeMatched` sees the matched endpoint's parameters. `afterMatched`
  sees only the parameters of its own path, so `afterMatched("*")` gets an empty map.
- **`X-HTTP-Method-Override`** is honored on `POST` only, and can be turned off with `config.http.methodOverride =
  false`.

### Errors

- **Default error responses** answer `text/plain` when the client accepts HTML; Javalin answers `text/html`. Messages
  are never HTML-escaped, so answering them as HTML would allow cross-site scripting whenever a message contains
  request data. Set `config.http.htmlErrorMessages = true` to answer `text/html` for messages that contain an HTML tag.
  Only do that if exception messages never include request data. Kalin's own "endpoint not found" message contains
  the request path and is always plain text. JSON negotiation (`Accept: application/json`) is unchanged.
- **Async timeouts** (`ctx.future`, `ctx.async`) always answer `500 Request timed out`, replacing any result set
  earlier. Error handlers for `500` run once.

### Uploads

- **Multipart limits**: `config.http.multipart.maxTotalRequestSize = -1` (the default) means the request is limited by
  `config.http.maxRequestSize`, which defaults to 1 MB. Raise one of them to accept larger uploads. A request with
  more than `maxParts` parts (default 1000) answers `413`. File parts of one request share an in-memory budget of
  4 × `maxInMemoryFileSize`; further files are written to disk. Temporary upload files are deleted after the response
  has been written.

### Proxies and CORS

- **Client IP behind proxies**: `config.contextResolver.useForwardedHeaders()` takes the **rightmost** entry of the
  forwarding chain (`Forwarded` / `X-Forwarded-For` / `X-Real-IP`) by default. That is the entry your own proxy
  added, which clients cannot forge. Pass `trustedProxies` (IP addresses or CIDR ranges) to skip your inner proxies,
  or `ForwardedIp.LEFTMOST` for Javalin's behavior. `ctx.forwardedFor()` returns the whole chain.

  ```kotlin
  config.contextResolver.useForwardedHeaders(ForwardedIp.RIGHTMOST, trustedProxies = listOf("10.0.0.0/8"))
  ```

- **CORS**: the plugin adds `Vary: Origin` to every response it handles, including requests without `Origin`, so
  shared caches don't mix them up. It logs a warning at startup when `reflectClientOrigin` and `allowCredentials` are
  both enabled.

### Static files

- **ETags** differ per content coding: an identity file and its gzip version get different strong ETags (`"…"` and
  `"…-gzip"`, `"…-deflate"`). `If-None-Match` matches any of them; `If-Range` matches the identity tag, since partial
  responses are never compressed. `304` responses carry `Vary: Accept-Encoding`.
- **Ranges** follow RFC 9110. A syntactically invalid range such as `bytes=5-3` is ignored (`200` with the whole
  file). Only unsatisfiable ranges answer `416`. When several ranges are requested and more than one can be satisfied, the whole file is sent with
  `200` (no `multipart/byteranges`).

### Bundled plugins

- `RedirectToLowercasePathPlugin` doesn't redirect when the target path would start with `//` or contain `\`. Browsers
  treat such paths as other hosts.

## Server and protocol

Kalin has its own HTTP/1.1 server instead of Jetty. It is stricter about the protocol than Jetty in a few places that
matter behind proxies:

- Chunked request bodies must use CRLF line endings and `;` before chunk extensions; anything else answers `400`.
- HTTP/1.1 requests need exactly one `Host` header, including absolute-form requests. The `*` request target is only
  accepted for `OPTIONS`.
- Limits: request line 8 KB (`414`), header section 16 KB and 100 fields (`431`), `config.server.maxConnections`,
  idle and request-head timeouts. `config.server.writeTimeout` applies to each stalled write, not to the whole
  response. Empty lines between requests don't extend the idle timeout, and an unread request body must be drained
  within one `requestTimeout` (up to `drainLimit`) for the connection to stay open.
- During `stop()`, responses carry `Connection: close`.
- Two engines (`LOOM`, `EVENT_LOOP`) and transports (NIO, native epoll). Endpoints marked `nonBlocking { }` can run on
  the event loop thread.
