package net.tegulis.kalin.router

import java.util.Collections
import net.tegulis.kalin.http.Handler
import net.tegulis.kalin.http.HandlerType
import net.tegulis.kalin.http.NotFoundResponse
import net.tegulis.kalin.security.RouteRole

/** Metadata attached to an [Endpoint]. */
public interface EndpointMetadata

/** The roles of an endpoint. */
public data class Roles(
    /** The roles. */
    val roles: Set<RouteRole>
) : EndpointMetadata

/** A registered handler: method (or lifecycle phase), path and handler, plus metadata such as roles. */
public open class Endpoint
@JvmOverloads
constructor(
    /** The handler type. */
    @JvmField public val method: HandlerType,
    /** The path pattern, for example `/users/{id}`. */
    @JvmField public val path: String,
    /** The handler. */
    @JvmField public val handler: Handler,
    metadata: Set<EndpointMetadata> = emptySet(),
) {
    private val metadata: Map<Class<*>, EndpointMetadata> = metadata.associateBy { it.javaClass }

    /** Returns the metadata of type [key], or null. */
    @Suppress("UNCHECKED_CAST") public fun <M : EndpointMetadata> metadata(key: Class<M>): M? = metadata[key] as M?

    /** All metadata. */
    public fun metadata(): Collection<EndpointMetadata> = metadata.values

    /** Returns a copy with [newMetadata] added. */
    public fun withMetadata(newMetadata: EndpointMetadata): Endpoint =
        Endpoint(method, path, handler, metadata.values.toSet() + newMetadata)

    /** The roles of this endpoint. */
    public val roles: Set<RouteRole>
        get() = metadata(Roles::class.java)?.roles ?: emptySet()

    override fun toString(): String = "$method $path"
}

/** Information about an added handler, passed to `config.events.handlerAdded`. */
public data class HandlerMetaInfo(
    val httpHandlerType: HandlerType,
    val path: String,
    val handler: Handler,
    val roles: Set<RouteRole>,
)

/** Wraps handlers, see `config.router.handlerWrapper`. */
public fun interface HandlerWrapper {
    /** Returns the handler to run for [endpoint]. */
    public fun wrap(endpoint: Endpoint): Handler
}

/** The endpoints visited by a request, in order. */
public class Endpoints internal constructor() {
    private val stack = ArrayList<Endpoint>(4)

    /** The endpoint currently executing. */
    public fun current(): Endpoint = stack.lastOrNull() ?: NO_ENDPOINT

    /** The last visited HTTP endpoint, useful in after handlers. */
    public fun lastHttpEndpoint(): Endpoint? = stack.findLast { it.method.isHttpMethod }

    /** All visited endpoints. */
    public fun list(): List<Endpoint> = Collections.unmodifiableList(stack)

    /** The matched HTTP endpoint (known already in `beforeMatched`), or the last visited one. */
    public fun matchedHttpEndpoint(): Endpoint? = matchedHttpEndpoint ?: lastHttpEndpoint()

    internal var matchedHttpEndpoint: Endpoint? = null
    internal var lastMatchedEndpoint: Endpoint? = null
    internal var lastPathParams: Map<String, String> = emptyMap()

    /** Each handler sees only the path params of its own path (an empty map for static paths). */
    internal fun add(endpoint: Endpoint, pathParams: Map<String, String>) {
        stack.add(endpoint)
        lastMatchedEndpoint = endpoint
        lastPathParams = pathParams
    }

    internal companion object {
        val NO_ENDPOINT = Endpoint(HandlerType.findOrDefault("NONE"), "", { throw NotFoundResponse() })
    }
}
