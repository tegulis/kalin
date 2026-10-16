package net.tegulis.kalin.config

/** A typed key for app data, see [KalinConfig.appData] and `Context.appData`. */
public class Key<T>(
    /** The unique id of the key. */
    public val id: String
) {
    override fun equals(other: Any?): Boolean = other is Key<*> && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Key($id)"
}
