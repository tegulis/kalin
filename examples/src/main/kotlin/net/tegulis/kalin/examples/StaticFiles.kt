package net.tegulis.kalin.examples

import net.tegulis.kalin.Kalin
import net.tegulis.kalin.staticfiles.Location

/** Serves `src/main/resources/public` from the classpath, plus an API route. */
fun main() {
    Kalin.create { config ->
            config.staticFiles.add("/public", Location.CLASSPATH)
            config.staticFiles.add { files ->
                files.hostedPath = "/downloads"
                files.directory = System.getProperty("java.io.tmpdir")
                files.location = Location.EXTERNAL
                files.directoryListing = true
            }
            config.routes.get("/api/time") { ctx -> ctx.result(java.time.Instant.now().toString()) }
        }
        .start(7070)
}
