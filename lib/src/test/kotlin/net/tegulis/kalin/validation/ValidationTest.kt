package net.tegulis.kalin.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import net.tegulis.kalin.testtools.testKalin

class ValidationTest :
    FunSpec({
        test("typed query params with defaults and checks") {
            testKalin {
                app {
                    routes.get("/int") { ctx ->
                        ctx.result(ctx.queryParamAsClass<Int>("value").getOrDefault(788).toString())
                    }
                    routes.get("/check") { ctx ->
                        ctx.result(
                            ctx.queryParamAsClass<Int>("param")
                                .check({ it > 5 }, ValidationError("OVER_LIMIT", mapOf("limit" to 5)))
                                .get()
                                .toString()
                        )
                    }
                    routes.get("/required") { ctx -> ctx.result(ctx.queryParamAsClass<Long>("id").get().toString()) }
                    routes.get("/nullable") { ctx ->
                        ctx.result(ctx.queryParamAsClass<Double>("d").allowNullable().get().toString())
                    }
                    routes.get("/enum") { ctx -> ctx.result(ctx.queryParamAsClass<Color>("c").get().name) }
                    routes.get("/list") { ctx -> ctx.result(ctx.queryParamsAsClass<Int>("n").get().sum().toString()) }
                    routes.get("/path/{id}") { ctx ->
                        ctx.result((ctx.pathParamAsClass<Int>("id").get() * 2).toString())
                    }
                    routes.get("/header") { ctx -> ctx.result(ctx.headerAsClass<Boolean>("X-Flag").get().toString()) }
                }
                client.get("/int?value=1").bodyAsText() shouldBe "1"
                client.get("/int").bodyAsText() shouldBe "788"
                client.get("/int?value=a").let {
                    it.code shouldBe 400
                    it.bodyAsText() shouldBe
                        """{"value":[{"message":"TYPE_CONVERSION_FAILED","args":{},"value":"a"}]}"""
                }
                client.get("/check?param=10").bodyAsText() shouldBe "10"
                client.get("/check?param=1").bodyAsText() shouldBe
                    """{"param":[{"message":"OVER_LIMIT","args":{"limit":5},"value":1}]}"""
                client.get("/required").bodyAsText() shouldBe
                    """{"id":[{"message":"NULLCHECK_FAILED","args":{},"value":null}]}"""
                client.get("/nullable").bodyAsText() shouldBe "null"
                client.get("/nullable?d=1.5").bodyAsText() shouldBe "1.5"
                client.get("/enum?c=RED").bodyAsText() shouldBe "RED"
                client.get("/enum?c=PINK").code shouldBe 400
                client.get("/list?n=1&n=2&n=3").bodyAsText() shouldBe "6"
                client.get("/path/21").bodyAsText() shouldBe "42"
                client.get("/header") { header("X-Flag", "true") }.bodyAsText() shouldBe "true"
            }
        }

        test("custom converters") {
            testKalin {
                app {
                    validation.register(Instant::class.java) { Instant.ofEpochMilli(it.toLong()) }
                    routes.get("/") { ctx ->
                        val from = ctx.queryParamAsClass<Instant>("from").get()
                        val to =
                            ctx.queryParamAsClass<Instant>("to")
                                .check({ it.isAfter(from) }, "'to' has to be after 'from'")
                                .get()
                        ctx.result("${to.toEpochMilli() - from.toEpochMilli()}")
                    }
                    routes.get("/missing") { ctx ->
                        ctx.result(ctx.queryParamAsClass<StringBuilder>("x").get().toString())
                    }
                }
                client.get("/?from=1000&to=3000").bodyAsText() shouldBe "2000"
                client.get("/?from=3000&to=1000").bodyAsText() shouldContain "'to' has to be after 'from'"
                client.get("/missing?x=1").code shouldBe 500
            }
        }

        test("collect errors and custom exceptions") {
            testKalin {
                app {
                    routes.get("/") { ctx ->
                        val a = ctx.queryParamAsClass<Int>("a").check({ it > 0 }, "POSITIVE")
                        val b = ctx.queryParamAsClass<Int>("b").check({ it < 10 }, "SMALL")
                        ctx.result(listOf(a, b).collectErrors().keys.sorted().toString())
                    }
                    routes.get("/throw") { ctx ->
                        ctx.result(
                            ctx.queryParamAsClass<Int>("a")
                                .getOrThrow { net.tegulis.kalin.http.ConflictResponse("custom") }
                                .toString()
                        )
                    }
                }
                client.get("/?a=-1&b=20").bodyAsText() shouldBe "[a, b]"
                client.get("/?a=1&b=2").bodyAsText() shouldBe "[]"
                client.get("/throw?a=x").code shouldBe 409
            }
        }

        test("validation exception mapper can be overridden") {
            testKalin {
                app {
                    routes.exception(ValidationException::class.java) { e, ctx ->
                        ctx.status(422).result("invalid: " + e.errors.keys)
                    }
                    routes.get("/") { ctx -> ctx.queryParamAsClass<Int>("n").get() }
                }
                client.get("/?n=x").let {
                    it.code shouldBe 422
                    it.bodyAsText() shouldBe "invalid: [n]"
                }
            }
        }
    })

enum class Color {
    RED,
    GREEN,
}
