package net.tegulis.kalin.examples

import net.tegulis.kalin.Kalin

/** The smallest Kalin app: `./gradlew :examples:run -Pexample=HelloWorld`. */
fun main() {
    Kalin.create { config -> config.routes.get("/") { ctx -> ctx.result("Hello World") } }.start(7070)
}
