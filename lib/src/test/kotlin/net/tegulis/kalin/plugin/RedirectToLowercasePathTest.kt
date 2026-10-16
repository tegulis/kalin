package net.tegulis.kalin.plugin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.tegulis.kalin.testtools.testKalin

class RedirectToLowercasePathTest :
    FunSpec({
        test("no redirects to other hosts") {
            testKalin {
                app {
                    bundledPlugins.enableRedirectToLowercasePaths()
                    routes.get("/{site}/about") { it.result("about") }
                }
                client.raw("GET /\\evil.com/ABOUT HTTP/1.1\r\nHost: x\r\n\r\n").response.let {
                    it.code shouldBe 404
                    it.header("Location") shouldBe null
                }
                client.raw("GET //evil.com/ABOUT HTTP/1.1\r\nHost: x\r\n\r\n").response.header("Location") shouldBe null
                client.get("/Site/ABOUT").let {
                    it.code shouldBe 301
                    it.header("Location") shouldBe "/Site/about"
                }
            }
        }
    })
