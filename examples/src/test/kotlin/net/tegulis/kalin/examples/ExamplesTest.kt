package net.tegulis.kalin.examples

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.inject.install
import net.tegulis.kalin.testtools.KalinTest
import net.tegulis.kalin.testtools.TestClient
import net.tegulis.kalin.testtools.TestMode
import net.tegulis.kalin.testtools.testKalin

/** A reusable test helper: the client comes from the testKalin context. */
context(client: TestClient)
fun usernames(): List<String> = client.get("/users").body<List<String>>()

class ExamplesTest :
    FunSpec({
        test("Ktor-style test of the REST example") {
            testKalin {
                app { userRoutes(UserController(UserDao())) }
                usernames() shouldBe listOf("Alice", "Bob", "Carol")
                client.get("/users/1").body<User>() shouldBe User("Alice", 1)
                client
                    .post("/users") { json(User("Dave", 0)) }
                    .let {
                        it.code shouldBe 201
                        it.body<User>().id shouldBe 4
                    }
                client.patch("/users/4") { json(User("David", 0)) }.code shouldBe 204
                client.get("/users/4").body<User>().name shouldBe "David"
                client.delete("/users/4").code shouldBe 204
                client.get("/users/4").code shouldBe 404
                client.get("/users/abc").code shouldBe 400
            }
        }

        test("Javalin-style test with a real server") {
            val app = Kalin.create { config -> config.userRoutes(UserController(UserDao())) }
            KalinTest.test(app, TestMode.REAL) { server, client ->
                server.port() shouldBe app.port()
                client.get("/users/2").body<User>().name shouldBe "Bob"
            }
        }

        test("ping test from the README") {
            testKalin {
                app { routes.get("/api/v1/ping") { ctx -> ctx.result("PONG") } }
                val response = client.get("/api/v1/ping")
                response.status shouldBe net.tegulis.kalin.http.HttpStatus.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.TEXT_PLAIN
                response.bodyAsText() shouldBe "PONG"
            }
        }

        test("SSE chat broadcasts to connected clients") {
            val chat = Chat()
            testKalin {
                app { chatRoutes(chat) }
                client.sse("/events") {
                    val deadline = System.currentTimeMillis() + 5000
                    while (chat.size == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5)
                    client.post("/send") { body("hello everyone") }.code shouldBe 204
                    take(1).single().data shouldBe "hello everyone"
                }
                client.get("/").bodyAsText() shouldContain "EventSource"
            }
        }

        test("precompiled jte templates") {
            testKalin {
                app { templateRoutes() }
                client.get("/").let {
                    it.bodyAsText() shouldContain "<h1>Kalin + jte</h1>"
                    it.bodyAsText() shouldContain "<li>precompiled</li>"
                }
            }
        }

        test("kotlin-inject wiring and swapping the component in tests") {
            val component = AppComponent::class.create { lang -> if (lang == "en") "Hello" else null }
            testKalin {
                app { install(component) }
                client.get("/greet/en/test").bodyAsText() shouldBe "Hello, test!"
                client.get("/greet/xx/test").code shouldBe 404
            }
        }

        test("context parameters") {
            testKalin {
                app { context(TodoRepository()) { todoRoutes() } }
                client.post("/todos") { json(Todo(1, "write tests")) }.body<Todo>().title shouldBe "write tests"
                client.get("/todos/1").body<Todo>() shouldBe Todo(1, "write tests")
                client.get("/todos/2").code shouldBe 404
            }
        }
    })
