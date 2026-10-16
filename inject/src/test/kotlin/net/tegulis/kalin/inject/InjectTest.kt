package net.tegulis.kalin.inject

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import net.tegulis.kalin.config.RoutesConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.NotFoundResponse
import net.tegulis.kalin.testtools.testKalin

interface UserStore {
    fun find(id: Long): String?
}

class MapUserStore(private val users: Map<Long, String>) : UserStore {
    override fun find(id: Long) = users[id]
}

@AppScope
@Inject
class UserRepository(private val store: UserStore) {
    val instance = counter++

    fun find(id: Long): String? = store.find(id)

    companion object {
        var counter = 0
    }
}

/** A request-scoped helper using context parameters next to injected services. */
context(ctx: Context)
fun userId(): Long = ctx.pathParam("id").toLong()

@Inject
class UserRoutes(private val repo: UserRepository) : KalinRoutes {
    override fun register(routes: RoutesConfig) {
        routes.get("/users/{id}") { ctx ->
            context(ctx) { ctx.result(repo.find(userId()) ?: throw NotFoundResponse("No user ${userId()}")) }
        }
    }
}

@Inject
class InfoRoutes(private val repo: UserRepository) : KalinRoutes {
    override fun register(routes: RoutesConfig) {
        routes.get("/repo-instance") { it.result(repo.instance.toString()) }
    }
}

@AppScope
@Component
abstract class AppComponent(@get:Provides val store: UserStore) : KalinComponent {
    @Provides @IntoSet protected fun users(r: UserRoutes): KalinRoutes = r

    @Provides @IntoSet protected fun info(r: InfoRoutes): KalinRoutes = r
}

class InjectTest :
    FunSpec({
        test("a generated component wires routes and scoped singletons") {
            val component = AppComponent::class.create(MapUserStore(mapOf(1L to "Alice")))
            testKalin {
                app { install(component) }
                client.get("/users/1").bodyAsText() shouldBe "Alice"
                client.get("/users/2").code shouldBe 404
                val first = client.get("/repo-instance").bodyAsText()
                client.get("/repo-instance").bodyAsText() shouldBe first
            }
        }

        test("tests can swap in a component with fakes") {
            val fake = AppComponent::class.create(MapUserStore(mapOf(1L to "Fake Alice")))
            testKalin {
                app { install(fake) }
                client.get("/users/1").bodyAsText() shouldBe "Fake Alice"
            }
        }
    })
