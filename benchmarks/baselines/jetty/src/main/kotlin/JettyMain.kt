import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback

data class Message(val message: String)

/** Plain Jetty 12 core handler, similar to the TFB jetty entry. */
fun main() {
    val mapper = ObjectMapper()
    val hello = ByteBuffer.wrap("Hello, World!".toByteArray())
    val server = Server()
    val config = HttpConfiguration().apply { sendServerVersion = true; sendDateHeader = true }
    val connector = ServerConnector(server, HttpConnectionFactory(config))
    connector.port = 8080
    connector.acceptQueueSize = 8192
    server.addConnector(connector)
    server.handler =
        object : Handler.Abstract.NonBlocking() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                when (request.httpURI.path) {
                    "/plaintext" -> {
                        response.headers.put(HttpHeader.CONTENT_TYPE, "text/plain")
                        response.write(true, hello.slice(), callback)
                    }
                    "/json" -> {
                        response.headers.put(HttpHeader.CONTENT_TYPE, "application/json")
                        response.write(true, ByteBuffer.wrap(mapper.writeValueAsBytes(Message("Hello, World!"))), callback)
                    }
                    else -> return false
                }
                return true
            }
        }
    server.start()
    server.join()
}
