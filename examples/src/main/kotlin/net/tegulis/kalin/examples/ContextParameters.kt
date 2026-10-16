package net.tegulis.kalin.examples

import kotlinx.serialization.Serializable
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.ktx.body
import net.tegulis.kalin.ktx.handler
import net.tegulis.kalin.ktx.pathParam
import net.tegulis.kalin.ktx.respondJson

@Serializable data class Todo(val id: Int, val title: String, val done: Boolean = false)

/** A service passed implicitly as a context parameter. */
class TodoRepository {
    private val todos = java.util.concurrent.ConcurrentHashMap<Int, Todo>()

    fun save(todo: Todo): Todo = todo.also { todos[it.id] = it }

    fun find(id: Int): Todo? = todos[id]
}

/** A request-scoped helper that combines two contexts. */
context(ctx: Context, repo: TodoRepository)
fun currentTodo(): Todo = repo.find(pathParam("id").toInt()) ?: throw net.tegulis.kalin.http.NotFoundResponse()

context(repo: TodoRepository)
fun KalinConfig.todoRoutes() {
    routes.post("/todos", handler { respondJson(repo.save(body<Todo>())) })
    routes.get("/todos/{id}", handler { respondJson(currentTodo()) })
}

/** `./gradlew :examples:run -Pexample=ContextParameters`. */
fun main() {
    Kalin.create { config -> context(TodoRepository()) { config.todoRoutes() } }.start(7070)
}
