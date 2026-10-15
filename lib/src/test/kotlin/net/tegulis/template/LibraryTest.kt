package net.tegulis.template

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class LibraryTest :
    FunSpec({
        test("message is generated") {
            val inputName = "world"
            val expectedOutput = "Hello world!"
            val actualOutput = Library.generateMessage(inputName)
            actualOutput shouldContain expectedOutput
        }
    })
