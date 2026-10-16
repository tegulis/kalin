package net.tegulis.kalin.examples

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.config.RoutesConfig
import net.tegulis.kalin.http.NotFoundResponse
import net.tegulis.kalin.inject.AppScope
import net.tegulis.kalin.inject.KalinComponent
import net.tegulis.kalin.inject.KalinRoutes
import net.tegulis.kalin.inject.install

/** Something the app needs from outside (for example a DataSource). */
fun interface GreetingSource {
    fun greeting(lang: String): String?
}

@AppScope
@Inject
class GreetingService(private val source: GreetingSource) {
    fun greet(lang: String, name: String): String =
        "${source.greeting(lang) ?: throw NotFoundResponse("Unknown language $lang")}, $name!"
}

@Inject
class GreetingRoutes(private val service: GreetingService) : KalinRoutes {
    override fun register(routes: RoutesConfig) {
        routes.get("/greet/{lang}/{name}") { ctx ->
            ctx.result(service.greet(ctx.pathParam("lang"), ctx.pathParam("name")))
        }
    }
}

/** The compile-time generated object graph (kotlin-inject + KSP). */
@AppScope
@Component
abstract class AppComponent(@get:Provides val source: GreetingSource) : KalinComponent {
    @Provides @IntoSet protected fun greetings(r: GreetingRoutes): KalinRoutes = r
}

/** `./gradlew :examples:run -Pexample=Injected`, then `curl localhost:7070/greet/fi/Kalin`. */
fun main() {
    val greetings = mapOf("en" to "Hello", "fi" to "Hei", "sv" to "Hej")
    val app = AppComponent::class.create { greetings[it] }
    Kalin.create { config -> config.install(app) }.start(7070)
}
