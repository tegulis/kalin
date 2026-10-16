package net.tegulis.kalin.examples

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.sse.SseClient

/** A tiny chat: messages posted to `/send` are broadcast to every `/events` stream. */
class Chat {
    private val clients = ConcurrentLinkedQueue<SseClient>()

    fun join(client: SseClient) {
        client.keepAlive()
        client.onClose { clients.remove(client) }
        clients.add(client)
        client.sendComment("welcome")
    }

    fun broadcast(message: String) {
        clients.forEach { it.sendEvent("message", message) }
    }

    val size: Int
        get() = clients.size
}

fun KalinConfig.chatRoutes(chat: Chat) {
    sse.heartbeat = 15.seconds
    routes.sse("/events") { client -> chat.join(client) }
    routes.post("/send") { ctx ->
        chat.broadcast(ctx.body())
        ctx.status(204)
    }
    routes.get("/") { ctx ->
        ctx.html(
            """
            <!DOCTYPE html><html><body><h1>Kalin SSE chat</h1>
            <input id="msg"><button onclick="fetch('/send',{method:'POST',body:msg.value})">Send</button>
            <ul id="log"></ul>
            <script>
              const events = new EventSource('/events');
              events.addEventListener('message', e => { const li = document.createElement('li'); li.textContent = e.data; log.append(li); });
            </script></body></html>
            """
                .trimIndent()
        )
    }
}

/** `./gradlew :examples:run -Pexample=SseChat`, then open http://localhost:7070 in two tabs. */
fun main() {
    val chat = Chat()
    Kalin.create { config -> config.chatRoutes(chat) }.start(7070)
}
