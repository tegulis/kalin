import io.javalin.Javalin

data class Message(val message: String)

/** Javalin 7 like the TFB javalin entry (virtual threads enabled). */
fun main() {
    Javalin.create { config ->
        config.jetty.port = 8080
        config.concurrency.useVirtualThreads = true
        config.startup.showJavalinBanner = false
        config.http.compressionStrategy = io.javalin.compression.CompressionStrategy.NONE
        config.routes.get("/plaintext") { ctx -> ctx.result("Hello, World!") }
        config.routes.get("/json") { ctx -> ctx.json(Message("Hello, World!")) }
    }.start()
}
