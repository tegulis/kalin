package net.tegulis.kalin.security

/** A role attached to endpoints; check it in a `beforeMatched` handler through `ctx.routeRoles()`. */
public interface RouteRole

/** Credentials from an `Authorization: Basic` header. */
public data class BasicAuthCredentials(
    /** The user name. */
    val username: String,
    /** The password. */
    val password: String,
)
