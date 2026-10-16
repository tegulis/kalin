package net.tegulis.kalin.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.testtools.testKalin

class StaticServerTest :
    FunSpec({
        val root = tempdir()
        File(root, "index.html").writeText("<h1>home</h1>")
        File(root, "docs").mkdirs()
        File(root, "docs/readme.txt").writeText("read me")
        File(root, "app").mkdirs()
        File(root, "app/index.html").writeText("<div id=app></div>")
        File(root, ".env").writeText("SECRET=1")

        test("parses arguments") {
            val o =
                ServerOptions.parse(
                    arrayOf(
                        "-p",
                        "9000",
                        "--bind",
                        "127.0.0.1",
                        "--no-listing",
                        "--hidden",
                        "--cache",
                        "60",
                        "-H",
                        "X-A: 1",
                        "--header=X-B: two",
                        "--basic-auth",
                        "u:p:w",
                        "--engine",
                        "event_loop",
                        "--idle-timeout",
                        "5",
                        "--spa",
                        "index.html",
                        root.absolutePath,
                    )
                )
            o.port shouldBe 9000
            o.host shouldBe "127.0.0.1"
            o.directoryListing shouldBe false
            o.hiddenFiles shouldBe true
            o.cacheMaxAge shouldBe 60
            o.headers shouldBe mapOf("X-A" to "1", "X-B" to "two")
            o.basicAuth shouldBe ("u" to "p:w")
            o.engine shouldBe Engine.EVENT_LOOP
            o.idleTimeout shouldBe 5.seconds
            o.directory shouldBe Path.of(root.absolutePath)
            o.validate()
            shouldThrow<UsageException> { ServerOptions.parse(arrayOf("--port", "70000")) }
            shouldThrow<UsageException> { ServerOptions.parse(arrayOf("--nope")) }
            shouldThrow<UsageException> { ServerOptions.parse(arrayOf("a", "b")) }
            shouldThrow<UsageException> { ServerOptions.parse(arrayOf("--port")) }
            shouldThrow<UsageException> { ServerOptions(directory = Path.of("/does/not/exist")).validate() }
            ServerOptions.parse(arrayOf("--help")).showHelp shouldBe true
            ServerOptions.parse(emptyArray()).host shouldBe "127.0.0.1"
            ServerOptions.parse(arrayOf("-b", "0.0.0.0")).host shouldBe "0.0.0.0"
        }

        test("basic auth credentials from a flag, a file or the environment") {
            val file = File(tempdir(), "auth").apply { writeText("file:pw:x\r\n") }
            val noEnv = emptyMap<String, String>()
            ServerOptions.parse(arrayOf("--basic-auth", "a:b"), noEnv).basicAuth shouldBe ("a" to "b")
            ServerOptions.parse(arrayOf("--basic-auth-file", file.path), noEnv).basicAuth shouldBe ("file" to "pw:x")
            ServerOptions.parse(emptyArray(), mapOf("KALIN_BASIC_AUTH" to "env:pw")).basicAuth shouldBe ("env" to "pw")
            ServerOptions.parse(emptyArray(), noEnv).basicAuth shouldBe null
            shouldThrow<UsageException> {
                ServerOptions.parse(arrayOf("--basic-auth", "a:b", "--basic-auth-file", file.path), noEnv)
            }
            shouldThrow<UsageException> {
                ServerOptions.parse(arrayOf("--basic-auth", "a:b"), mapOf("KALIN_BASIC_AUTH" to "env:pw"))
            }
            shouldThrow<UsageException> { ServerOptions.parse(arrayOf("--basic-auth-file", "/does/not/exist"), noEnv) }
            shouldThrow<UsageException> { ServerOptions.parse(emptyArray(), mapOf("KALIN_BASIC_AUTH" to "nocolon")) }
        }

        test("serves files, index pages, listings and hides dotfiles") {
            testKalin {
                app { staticServer(ServerOptions(directory = root.toPath(), accessLog = false)) }
                client.get("/").bodyAsText() shouldBe "<h1>home</h1>"
                client.get("/docs/readme.txt").let {
                    it.bodyAsText() shouldBe "read me"
                    it.header("Server") shouldBe "kalin-server"
                }
                client.get("/docs/").bodyAsText() shouldContain "readme.txt"
                client.get("/.env").code shouldBe 404
                client.get("/missing").bodyAsText() shouldBe "Not Found"
                client.get("/missing") { header("Accept", "text/html") }.bodyAsText() shouldContain
                    "<h1>404 Not Found</h1>"
            }
        }

        test("options: no listing, cache, headers, cors, basic auth, context path, spa") {
            testKalin {
                app {
                    staticServer(
                        ServerOptions(
                            directory = root.toPath(),
                            accessLog = false,
                            directoryListing = false,
                            cacheMaxAge = 3600,
                            headers = mapOf("X-Frame-Options" to "DENY"),
                            cors = true,
                            basicAuth = "user" to "pw",
                            contextPath = "/files",
                            spaFallback = "app/index.html",
                        )
                    )
                }
                val auth = "Basic " + Base64.getEncoder().encodeToString("user:pw".toByteArray())
                client.get("/files/").code shouldBe 401
                client
                    .get("/files/docs/readme.txt") {
                        header("Authorization", auth)
                        header("Origin", "https://example.com")
                    }
                    .let {
                        it.bodyAsText() shouldBe "read me"
                        it.header("Cache-Control") shouldBe "max-age=3600"
                        it.header("X-Frame-Options") shouldBe "DENY"
                        it.header("Access-Control-Allow-Origin") shouldBe "*"
                    }
                client.get("/files/docs/") { header("Authorization", auth) }.code shouldBe 404
                client
                    .get("/files/some/client/route") {
                        header("Authorization", auth)
                        header("Accept", "text/html")
                    }
                    .bodyAsText() shouldBe "<div id=app></div>"
                client.get("/docs/readme.txt") { header("Authorization", auth) }.code shouldBe 404
            }
        }

        test("access log escapes client values and logs only authenticated users") {
            logEscape("plain /ä") shouldBe "plain /ä"
            logEscape("a\"b\\c\r\nd\u0000\u007f\u0085\u2028") shouldBe "a\\x22b\\x5Cc\\x0D\\x0Ad\\x00\\x7F\\x85\\u2028"
            val lines = LinkedBlockingQueue<String>()
            testKalin {
                app {
                    staticServer(
                        ServerOptions(directory = root.toPath(), basicAuth = "user" to "pw"),
                        accessLogSink = { lines.add(it) },
                    )
                }
                fun basic(credentials: String) =
                    "Basic " + Base64.getEncoder().encodeToString(credentials.toByteArray())
                client
                    .get("/") {
                        header("Authorization", basic("evil\r\n127.0.0.1 - admin:x"))
                        header("User-Agent", "agent\" \"\tx\\")
                        header("Referer", "https://r/\"q")
                    }
                    .code shouldBe 401
                lines.poll(5, TimeUnit.SECONDS)!!.let {
                    it shouldContain " - - ["
                    it shouldContain "\"https://r/\\x22q\" \"agent\\x22 \\x22\\x09x\\x5C\""
                    it shouldNotContain "evil"
                }
                client.get("/") { header("Authorization", basic("user:pw")) }.code shouldBe 200
                lines.poll(5, TimeUnit.SECONDS)!! shouldContain " - user ["
            }
        }
    })
