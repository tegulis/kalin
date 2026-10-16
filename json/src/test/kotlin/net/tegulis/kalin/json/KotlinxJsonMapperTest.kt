package net.tegulis.kalin.json

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Stream
import kotlin.reflect.typeOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.tegulis.kalin.http.NotFoundResponse
import net.tegulis.kalin.testtools.testKalin

@Serializable data class User(val id: Int, val name: String, val email: String? = null)

@Serializable data class Wrapper(val users: List<User>, val meta: Map<String, String>)

@Serializable data class Text(val value: String)

class KotlinxJsonMapperTest :
    FunSpec({
        val mapper = KotlinxJsonMapper()

        test("is registered automatically") { (JsonMapper.default() is KotlinxJsonMapper) shouldBe true }

        test("bytes and strings agree, including escaping and unicode") {
            val samples =
                listOf(
                    "plain",
                    "quote \" backslash \\ slash /",
                    "control \u0000 \u0001 \b \t \n \u000C \r end",
                    "unicode æøå 中文 😀 emoji",
                    "\u007f\u0080߿ࠀ￿",
                    "",
                )
            for (s in samples) {
                val viaBytes = String(mapper.toJsonBytes(Text(s), typeOf<Text>()), Charsets.UTF_8)
                viaBytes shouldBe Json.encodeToString(Text.serializer(), Text(s))
                Json.decodeFromString(Text.serializer(), viaBytes).value shouldBe s
            }
        }

        test("numbers and large outputs") {
            val users = (0 until 500).map { User(it, "user $it", if (it % 2 == 0) "u$it@example.com" else null) }
            val type = typeOf<List<User>>()
            repeat(3) {
                val bytes = mapper.toJsonBytes(users, type)
                String(bytes) shouldBe KotlinxJsonMapper.DEFAULT_JSON.encodeToString(users)
            }
            String(
                mapper.toJsonBytes(
                    mapOf("min" to Long.MIN_VALUE, "max" to Long.MAX_VALUE, "d" to 1.5),
                    typeOf<Map<String, Any>>(),
                )
            ) shouldBe """{"min":-9223372036854775808,"max":9223372036854775807,"d":1.5}"""
        }

        test("dynamic values: maps, lists, nested serializable values") {
            mapper.toJsonString(
                mapOf("a" to 1, "b" to listOf(true, null, "x"), "u" to User(1, "n")),
                typeOf<Map<String, Any?>>(),
            ) shouldBe """{"a":1,"b":[true,null,"x"],"u":{"id":1,"name":"n","email":null}}"""
            mapper.fromJsonString<Map<String, Any?>>(
                """{"a":1,"b":[1.5,"x",false],"c":null}""",
                typeOf<Map<String, Any?>>(),
            ) shouldBe mapOf("a" to 1, "b" to listOf(1.5, "x", false), "c" to null)
        }

        test("missing serializer gives a clear error") {
            class NotSerializable(val x: Int)
            val e =
                runCatching { mapper.fromJsonString<NotSerializable>("{}", typeOf<NotSerializable>()) }
                    .exceptionOrNull()!!
            e.message!! shouldContain "@Serializable"
        }

        test("ctx.json, bodyAsClass, bodyValidator and writeJsonStream") {
            testKalin {
                app {
                    routes.post("/user") { ctx ->
                        val user = ctx.bodyAsClass<User>()
                        ctx.json(user.copy(name = user.name.uppercase()))
                    }
                    routes.post("/validated") { ctx ->
                        val user = ctx.bodyValidator<User>().check({ it.id > 0 }, "ID_MUST_BE_POSITIVE").get()
                        ctx.json(user)
                    }
                    routes.post("/stream") { ctx -> ctx.json(ctx.bodyStreamAsClass<List<User>>().size) }
                    routes.get("/list") { ctx -> ctx.json(listOf(User(1, "a"), User(2, "b"))) }
                    routes.get("/wrapper") { ctx -> ctx.json(Wrapper(listOf(User(1, "a")), mapOf("k" to "v"))) }
                    routes.get("/any") { ctx -> ctx.json(mapOf("n" to 1) as Any) }
                    routes.get("/jsonstream") { ctx -> ctx.jsonStream(listOf(User(3, "c"))) }
                    routes.get("/write") { ctx -> ctx.writeJsonStream(Stream.of(User(1, "a"), User(2, "b"))) }
                }
                client.post("/user") { json(User(1, "kalin")) }.body<User>() shouldBe User(1, "KALIN")
                client
                    .post("/validated") { json(User(0, "x")) }
                    .let {
                        it.code shouldBe 400
                        it.bodyAsText() shouldBe
                            """{"REQUEST_BODY":[{"message":"ID_MUST_BE_POSITIVE","args":{},"value":"User(id=0, name=x, email=null)"}]}"""
                    }
                client.post("/validated") { jsonString("{not json") }.bodyAsText() shouldContain
                    "DESERIALIZATION_FAILED"
                client.post("/stream") { json(listOf(User(1, "a"), User(2, "b"), User(3, "c"))) }.bodyAsText() shouldBe
                    "3"
                client.get("/list").body<List<User>>() shouldBe listOf(User(1, "a"), User(2, "b"))
                client.get("/wrapper").bodyAsText() shouldBe
                    """{"users":[{"id":1,"name":"a","email":null}],"meta":{"k":"v"}}"""
                client.get("/any").bodyAsText() shouldBe """{"n":1}"""
                client.get("/jsonstream").body<List<User>>() shouldBe listOf(User(3, "c"))
                client.get("/write").body<List<User>>() shouldBe listOf(User(1, "a"), User(2, "b"))
            }
        }

        test("Baeldung REST microservice example works with changed imports") {
            val users = ConcurrentHashMap<Int, User>()
            val ids = AtomicInteger()
            testKalin {
                app {
                    routes.get("/users") { ctx -> ctx.json(users.values.sortedBy { it.id }) }
                    routes.get("/users/{id}") { ctx ->
                        ctx.json(
                            users[ctx.pathParamAsClass<Int>("id").get()] ?: throw NotFoundResponse("User not found")
                        )
                    }
                    routes.post("/users") { ctx ->
                        val user = ctx.bodyAsClass<User>()
                        val id = ids.incrementAndGet()
                        users[id] = user.copy(id = id)
                        ctx.status(201).json(users[id]!!)
                    }
                    routes.delete("/users/{id}") { ctx ->
                        users.remove(ctx.pathParamAsClass<Int>("id").get())
                        ctx.status(204)
                    }
                }
                client.post("/users") { json(User(0, "Alice", "alice@example.com")) }.code shouldBe 201
                client.post("/users") { json(User(0, "Bob")) }.body<User>().id shouldBe 2
                client.get("/users").body<List<User>>().map { it.name } shouldBe listOf("Alice", "Bob")
                client.get("/users/1").body<User>().email shouldBe "alice@example.com"
                client
                    .get("/users/9") { header("Accept", "application/json") }
                    .let {
                        it.code shouldBe 404
                        it.bodyAsText() shouldContain "User not found"
                    }
                client.delete("/users/1").code shouldBe 204
                client.get("/users").body<List<User>>().size shouldBe 1
            }
        }

        test("SSE sends objects as JSON") {
            testKalin {
                app { routes.sse("/sse") { it.sendEvent("user", User(7, "sse")) } }
                client.sse("/sse") { take(1).single().data shouldBe """{"id":7,"name":"sse","email":null}""" }
            }
        }
    })
