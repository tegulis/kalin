package net.tegulis.kalin.router

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URLEncoder
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.apibuilder.get
import net.tegulis.kalin.apibuilder.path
import net.tegulis.kalin.http.EndpointNotFound
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.http.NotFoundResponse
import net.tegulis.kalin.testtools.testKalin

class RoutingTest :
    FunSpec({
        test("hello world") {
            testKalin {
                app { routes.get("/hello") { it.result("Hello World") } }
                val r = client.get("/hello")
                r.status shouldBe HttpStatus.OK
                r.bodyAsText() shouldBe "Hello World"
            }
        }

        test("all verbs including custom methods") {
            testKalin {
                val propfind = HandlerType.findOrCreate("PROPFIND")
                app {
                    for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "QUERY", "TRACE")) {
                        routes.addHttpHandler(HandlerType.findOrCreate(m), "/mapped") { it.result(m) }
                    }
                    routes.addHttpHandler(propfind, "/dav/{res}") { it.result("PROPFIND: " + it.pathParam("res")) }
                }
                for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "QUERY", "TRACE")) {
                    client.request(m, "/mapped").bodyAsText() shouldBe m
                }
                client.request("PROPFIND", "/dav/file").bodyAsText() shouldBe "PROPFIND: file"
                client.request("HEAD", "/mapped").code shouldBe 200
            }
        }

        test("unmapped verbs return 404 with message") {
            testKalin {
                app {}
                for (m in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
                    val r = client.request(m, "/unmapped")
                    r.code shouldBe 404
                    r.bodyAsText() shouldBe "Endpoint $m /unmapped not found"
                }
            }
        }

        test("EndpointNotFound can be handled differently than other 404s") {
            testKalin {
                app {
                    routes.get("/user") { throw NotFoundResponse("User not found") }
                    routes.exception(EndpointNotFound::class.java) { _, ctx ->
                        ctx.status(HttpStatus.METHOD_NOT_ALLOWED)
                    }
                }
                client.get("/user").let {
                    it.code shouldBe 404
                    it.bodyAsText() shouldBe "User not found"
                }
                client.get("/guild").code shouldBe 405
            }
        }

        test("urls are case sensitive by default and insensitive when configured") {
            testKalin {
                app { routes.get("/My-Url") { it.result("OK") } }
                client.get("/My-Url").code shouldBe 200
                client.get("/my-url").code shouldBe 404
            }
            testKalin {
                app {
                    router.caseInsensitiveRoutes = true
                    routes.get("/paTh") { it.result("ok") }
                    routes.get("/patH/<param>") { it.result(it.pathParam("param")) }
                }
                client.get("/PATH").bodyAsText() shouldBe "ok"
                client.get("/pAtH/vAlUe").bodyAsText() shouldBe "vAlUe"
            }
        }

        test("filters are executed in order") {
            testKalin {
                app {
                    routes.before { it.result("1") }
                    routes.before { it.result(it.result() + "2") }
                    routes.get("/hello") { it.result(it.result() + "Hello") }
                    routes.after { it.result(it.result() + "3") }
                }
                client.get("/hello").bodyAsText() shouldBe "12Hello3"
            }
        }

        test("invalid path patterns throw on creation") {
            shouldThrow<IllegalArgumentException> { Kalin.create { it.routes.get("/:test") {} } }
            for (p in listOf("/{", "/}", "/>", "/<", "/</>")) {
                shouldThrow<MissingBracketsException> { Kalin.create { it.routes.get(p) {} } }
            }
            shouldThrow<WildcardBracketAdjacentException> { Kalin.create { it.routes.get("/*{name}") {} } }
            shouldThrow<ParameterNamesNotUniqueException> { Kalin.create { it.routes.get("/{param}/demo/<param>") {} } }
        }

        test("wildcards") {
            testKalin {
                app {
                    routes.get("/mid/*/test") { it.result("middle") }
                    routes.get("/*/test") { it.result("first") }
                    routes.get("/test/*") { it.result("last") }
                    routes.get("/hello:world") { it.result("colon") }
                }
                client.get("/tast/test").bodyAsText() shouldBe "first"
                client.get("/test").bodyAsText() shouldBe "Endpoint GET /test not found"
                client.get("/test/1").bodyAsText() shouldBe "last"
                client.get("/mid/en/test").bodyAsText() shouldBe "middle"
                client.get("/hello:world").bodyAsText() shouldBe "colon"
            }
        }

        test("path params") {
            testKalin {
                app {
                    routes.get("/two/{p1}AND{p2}") { it.result(it.pathParam("p1") + "," + it.pathParam("p2")) }
                    routes.get("/hi-{name}") { it.result(it.pathParam("name")) }
                    routes.get("/hw-{name}-*") { it.result(it.pathParam("name")) }
                    routes.get("/star-{name*}") { it.result(it.pathParam("name*")) }
                    routes.get("/sub/{name}*") { it.result(it.pathParam("name")) }
                    routes.get("/slashes/<name>") { it.result(it.pathParam("name")) }
                    routes.get("/case/{a}/{A}") { it.result("${it.pathParam("a")}-${it.pathParam("A")}") }
                    routes.get("/{p1}") { it.result(it.pathParamMap().toString()) }
                }
                client.get("/param1").bodyAsText() shouldBe "{p1=param1}"
                client.get("/two/aANDb").bodyAsText() shouldBe "a,b"
                client.get("/hi-world").bodyAsText() shouldBe "world"
                client.get("/hw-world").bodyAsText() shouldBe "{p1=hw-world}"
                client.get("/hw-world-more").bodyAsText() shouldBe "world"
                client.get("/star-x").bodyAsText() shouldBe "x"
                client.get("/sub/text").bodyAsText() shouldBe "text"
                client.get("/sub/text/two").bodyAsText() shouldBe "text"
                client.get("/slashes/hi/with/slashes").bodyAsText() shouldBe "hi/with/slashes"
                client.get("/case/a/B").bodyAsText() shouldBe "a-B"
                client.get("/" + URLEncoder.encode("TE/ST", "UTF-8")).bodyAsText() shouldBe "{p1=TE/ST}"
                client.get("/a+b").bodyAsText() shouldBe "{p1=a+b}"
                client.get("/%+").let {
                    it.code shouldBe 400
                    it.bodyAsText() shouldContain "Bad Request"
                }
            }
        }

        test("sub-path wildcard and non sub-path wildcards in filters") {
            testKalin {
                app {
                    routes.get("/p") { it.result("1") }
                    routes.get("/p-test") { it.result("2") }
                    routes.after("/p*") { if (!it.path().startsWith("/partners")) it.result("${it.result()}AFTER") }
                    routes.apiBuilder {
                        net.tegulis.kalin.apibuilder.after("/partners/{pp}*") { it.result("${it.result()} - after") }
                        path("/partners/{pp}") {
                            get { it.result("root") }
                            get("/api") { it.result("api") }
                        }
                    }
                }
                client.get("/p").bodyAsText() shouldBe "1AFTER"
                client.get("/p-test").bodyAsText() shouldBe "2AFTER"
                client.get("/partners/microsoft").bodyAsText() shouldBe "root - after"
                client.get("/partners/microsoft/api").bodyAsText() shouldBe "api - after"
            }
        }

        test("registration order decides between static and dynamic routes") {
            testKalin {
                app {
                    routes.get("/users/{id}") { it.result("dynamic " + it.pathParam("id")) }
                    routes.get("/users/me") { it.result("static") }
                    routes.get("/items/all") { it.result("static") }
                    routes.get("/items/{id}") { it.result("dynamic") }
                }
                client.get("/users/me").bodyAsText() shouldBe "dynamic me"
                client.get("/items/all").bodyAsText() shouldBe "static"
                client.get("/items/1").bodyAsText() shouldBe "dynamic"
            }
        }

        test("trailing slashes and multiple slashes") {
            testKalin {
                app {
                    routes.get("/a") { it.result("a") }
                    routes.get("/b/{x}") { it.result(it.pathParam("x")) }
                }
                client.get("/a/").bodyAsText() shouldBe "a"
                client.get("/b/1/").bodyAsText() shouldBe "1"
                client.get("//a").code shouldBe 404
            }
            testKalin {
                app {
                    router.ignoreTrailingSlashes = false
                    router.treatMultipleSlashesAsSingleSlash = true
                    routes.get("/") { it.result("root") }
                    routes.get("/a") { it.result("a") }
                    routes.get("/b/{x}") { it.result(it.pathParam("x")) }
                }
                client.get("/").bodyAsText() shouldBe "root"
                client.get("/a/").code shouldBe 404
                client.get("//a").bodyAsText() shouldBe "a"
                client.get("/b//2").bodyAsText() shouldBe "2"
            }
        }

        test("context path") {
            testKalin {
                app {
                    router.contextPath = "/api"
                    routes.get("/hello") { it.result(it.contextPath() + "|" + it.path()) }
                }
                client.get("/api/hello").bodyAsText() shouldBe "/api|/api/hello"
                client.get("/hello").code shouldBe 404
            }
        }

        test("405 instead of 404 when configured") {
            testKalin {
                app {
                    http.prefer405over404 = true
                    routes.post("/x") { it.result("post") }
                    routes.put("/x") { it.result("put") }
                }
                val r = client.get("/x")
                r.code shouldBe 405
                r.header("Allow") shouldBe "POST, PUT"
                client.get("/y").code shouldBe 404
            }
        }

        test("endpoint metadata and endpoint() info") {
            testKalin {
                app {
                    routes.get("/hello/{name}") { ctx ->
                        ctx.result("${ctx.endpoint().method} ${ctx.endpoint().path} ${ctx.endpoints().list().size}")
                    }
                }
                client.get("/hello/x").bodyAsText() shouldBe "GET /hello/{name} 1"
            }
        }

        test("handler wrapper wraps all handlers") {
            testKalin {
                app {
                    router.handlerWrapper { endpoint ->
                        net.tegulis.kalin.http.Handler { ctx ->
                            ctx.attribute("w", (ctx.attribute<String>("w") ?: "") + endpoint.method.name + ",")
                            endpoint.handler.handle(ctx)
                        }
                    }
                    routes.before {}
                    routes.get("/") { it.result(it.attribute<String>("w")!!) }
                }
                client.get("/").bodyAsText() shouldBe "BEFORE,GET,"
            }
        }
    })
