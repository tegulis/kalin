package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.foreign.Arena
import java.nio.file.Files
import net.tegulis.kalin.config.ServerConfig
import net.tegulis.kalin.engine.epoll.Epoll
import net.tegulis.kalin.engine.epoll.EpollEventLoop
import net.tegulis.kalin.engine.epoll.EpollSocket
import net.tegulis.kalin.engine.epoll.Native

class EpollFixesTest :
    FunSpec({
        fun runtime() =
            ServerRuntime(
                ServerConfig(),
                object : ExchangeHandler {
                    override fun handle(exchange: Http1Exchange) {}
                },
            )

        if (Epoll.available()) {
            test("sendfile handles close their descriptor exactly once") {
                val fileA = Files.createTempFile("kalin-a", ".txt").also { Files.writeString(it, "aaaa") }
                val fileB = Files.createTempFile("kalin-b", ".txt").also { Files.writeString(it, "bbbb") }
                val loop = EpollEventLoop("test", runtime(), -1)
                Arena.ofConfined().use { arena ->
                    val s = Native.Scratch(arena)
                    val socket = EpollSocket(Native.eventfd(s), loop, null)
                    val handle = socket.openFile(fileA)
                    handle.close()
                    // The lowest free descriptor is reused: this file most likely gets the number just released
                    val other = Native.openRead(s, arena, fileB.toString())
                    try {
                        handle.close()
                        socket.close()
                        val buf = ByteArray(16)
                        Native.read(s, other, buf, 0, buf.size) shouldBe 4
                    } finally {
                        Native.close(s, other)
                    }
                }
                Files.delete(fileA)
                Files.delete(fileB)
            }
        }
    })
