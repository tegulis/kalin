package net.tegulis.kalin.examples

import net.tegulis.kalin.Kalin

/** The smallest Kalin app. */
fun main() {
    Kalin.create { config ->
            config.routes.get("/") { ctx -> ctx.result("Hello World") }
            config.routes.get("/hello/{name}") { ctx -> ctx.result("Hello ${ctx.pathParam("name")}") }
            config.routes.post("/echo") { ctx -> ctx.result(ctx.body()) }
            config.routes.get("/json") { ctx -> ctx.json(mapOf("message" to "Hello, World!")) }
            config.routes.sse("/events") { client ->
                client.sendEvent("hello", "world")
                client.sendEvent("second", "event", "2")
            }
        }
        .start(7070)
}
