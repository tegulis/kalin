package net.tegulis.kalin.config

import net.tegulis.kalin.router.HandlerWrapper

/** Routing configuration. */
public class RouterConfig {
    /** The context path, for example `/blog` when hosting the app on a sub path. */
    @JvmField public var contextPath: String = "/"

    /** Treat `/path` and `/path/` as the same path. */
    @JvmField public var ignoreTrailingSlashes: Boolean = true

    /** Treat `/path//sub` and `/path/sub` as the same path. */
    @JvmField public var treatMultipleSlashesAsSingleSlash: Boolean = false

    /** Match paths case-insensitively. */
    @JvmField public var caseInsensitiveRoutes: Boolean = false

    internal var handlerWrapper: HandlerWrapper? = null

    /** Wraps every handler (before, endpoint, after, ...), for example to propagate a `ScopedValue`. */
    public fun handlerWrapper(wrapper: HandlerWrapper): RouterConfig = apply { handlerWrapper = wrapper }
}
