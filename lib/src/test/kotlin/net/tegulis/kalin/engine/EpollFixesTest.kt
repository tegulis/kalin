package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.foreign.Arena
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.tegulis.kalin.config.ServerConfig
import net.tegulis.kalin.engine.epoll.Epoll
import net.tegulis.kalin.engine.epoll.EpollEventLoop
import net.tegulis.kalin.engine.epoll.EpollSocket
import net.tegulis.kalin.engine.epoll.Errno
import net.tegulis.kalin.engine.epoll.Native
import net.tegulis.kalin.engine.epoll.NativeException

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

            test("the local address of a connection is known without touching the socket later") {
                val (listenFd, port) =
                    Arena.ofConfined().use {
                        val s = Native.Scratch(it)
                        val fd = EpollEventLoop.listen(s, "127.0.0.1", 0, 16)
                        fd to Native.localAddress(s, fd)!!.port
                    }
                val loop = EpollEventLoop("test-local", runtime(), -1)
                val socket = EpollSocket(listenFd, loop, null)
                socket.close()
                // Handler threads may ask after the connection is gone; the address was taken on the loop thread
                socket.localAddress?.port shouldBe port
            }

            test("failing accepts pause the listener instead of spinning, then accepting resumes") {
                val listenFd = Arena.ofConfined().use { EpollEventLoop.listen(Native.Scratch(it), "127.0.0.1", 0, 16) }
                val loop = EpollEventLoop("test-accept", runtime(), listenFd)
                val port = loop.localAddress!!.port
                val failing = AtomicBoolean(true)
                val attempts = AtomicInteger()
                val accepted = AtomicInteger()
                val realAccept = loop.accept
                loop.accept = { fd ->
                    attempts.incrementAndGet()
                    if (failing.get()) throw NativeException("accept4", Errno.EMFILE)
                    realAccept(fd).also { if (it >= 0) accepted.incrementAndGet() }
                }
                loop.start()
                try {
                    Socket("127.0.0.1", port).use {
                        Thread.sleep(1000)
                        // About one retry per backoff period; a level-triggered spin would make millions
                        (attempts.get() in 2..30) shouldBe true
                        failing.set(false)
                        val deadline = System.nanoTime() + 5_000_000_000
                        while (accepted.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
                        accepted.get() shouldBe 1
                    }
                } finally {
                    loop.shutdown()
                    loop.thread.join(2000)
                }
            }
        }
    })
