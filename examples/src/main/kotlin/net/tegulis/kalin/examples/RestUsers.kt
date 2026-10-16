package net.tegulis.kalin.examples

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.apibuilder.delete
import net.tegulis.kalin.apibuilder.get
import net.tegulis.kalin.apibuilder.patch
import net.tegulis.kalin.apibuilder.path
import net.tegulis.kalin.apibuilder.post
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.http.NotFoundResponse

/*
 * A port of Baeldung's "Creating REST Microservices with Javalin": only the imports changed (io.javalin ->
 * net.tegulis.kalin) and the model is @Serializable for the default kotlinx.serialization JSON mapper.
 */

@Serializable data class User(val name: String, val id: Int)

/** An in-memory user store. */
class UserDao {
    private val users = ConcurrentHashMap<Int, User>()
    private val lastId = AtomicInteger()

    init {
        listOf("Alice", "Bob", "Carol").forEach { save(it) }
    }

    fun save(name: String): User {
        val id = lastId.incrementAndGet()
        return User(name, id).also { users[id] = it }
    }

    fun findById(id: Int): User? = users[id]

    fun findByName(name: String): User? = users.values.find { it.name == name }

    fun update(id: Int, user: User) {
        users[id] = User(user.name, id)
    }

    fun delete(id: Int) {
        users.remove(id)
    }

    fun all(): List<User> = users.values.sortedBy { it.id }
}

/** Handlers in a controller object, like in the Baeldung article. */
class UserController(private val dao: UserDao) {
    fun fetchAllUsernames(ctx: Context) {
        ctx.json(dao.all().map { it.name })
    }

    fun fetchById(ctx: Context) {
        val id = ctx.pathParamAsClass<Int>("id").get()
        ctx.json(dao.findById(id) ?: throw NotFoundResponse("User $id not found"))
    }

    fun create(ctx: Context) {
        val user = ctx.bodyAsClass<User>()
        ctx.status(HttpStatus.CREATED).json(dao.save(user.name))
    }

    fun update(ctx: Context) {
        dao.update(ctx.pathParamAsClass<Int>("id").get(), ctx.bodyAsClass<User>())
        ctx.status(HttpStatus.NO_CONTENT)
    }

    fun delete(ctx: Context) {
        dao.delete(ctx.pathParamAsClass<Int>("id").get())
        ctx.status(HttpStatus.NO_CONTENT)
    }
}

/** The routes, usable from `main` and from tests. */
fun KalinConfig.userRoutes(controller: UserController) {
    routes.apiBuilder {
        path("/users") {
            get(controller::fetchAllUsernames)
            post(controller::create)
            path("/{id}") {
                get(controller::fetchById)
                patch(controller::update)
                delete(controller::delete)
            }
        }
    }
}

/** `./gradlew :examples:run -Pexample=RestUsers`, then `curl localhost:7070/users/1`. */
fun main() {
    val controller = UserController(UserDao())
    Kalin.create { config -> config.userRoutes(controller) }.start(7070)
}
