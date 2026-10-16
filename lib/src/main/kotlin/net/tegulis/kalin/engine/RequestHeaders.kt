package net.tegulis.kalin.engine

/**
 * Request headers in arrival order. Name lookup is case-insensitive. Instances are read-only views created by the
 * parser.
 */
public class RequestHeaders internal constructor(initialCapacity: Int = 16) : Iterable<Pair<String, String>> {
    private var names: Array<String?> = arrayOfNulls(initialCapacity)
    private var values: Array<String?> = arrayOfNulls(initialCapacity)

    /** The number of header fields. */
    public var size: Int = 0
        private set

    internal fun add(name: String, value: String) {
        if (size == names.size) {
            names = names.copyOf(size * 2)
            values = values.copyOf(size * 2)
        }
        names[size] = name
        values[size] = value
        size++
    }

    /** The name of the header field at [index] as sent by the client. */
    public fun name(index: Int): String = names[index]!!

    /** The value of the header field at [index]. */
    public fun value(index: Int): String = values[index]!!

    /** Returns the first value of [name], or null. */
    public operator fun get(name: String): String? {
        for (i in 0 until size) {
            val n = names[i]!!
            if (n === name || n.equals(name, ignoreCase = true)) return values[i]
        }
        return null
    }

    /** Returns all values of [name] in order. */
    public fun getAll(name: String): List<String> {
        var result: ArrayList<String>? = null
        for (i in 0 until size) {
            if (names[i]!!.equals(name, ignoreCase = true)) {
                if (result == null) result = ArrayList(2)
                result.add(values[i]!!)
            }
        }
        return result ?: emptyList()
    }

    /** Returns true if a header named [name] is present. */
    public operator fun contains(name: String): Boolean = get(name) != null

    /** The distinct header names, in arrival order. */
    public fun names(): Set<String> {
        val set = LinkedHashSet<String>()
        for (i in 0 until size) set.add(names[i]!!)
        return set
    }

    override fun iterator(): Iterator<Pair<String, String>> =
        object : Iterator<Pair<String, String>> {
            var i = 0

            override fun hasNext() = i < size

            override fun next(): Pair<String, String> {
                if (i >= size) throw NoSuchElementException()
                return Pair(names[i]!!, values[i++]!!)
            }
        }

    override fun toString(): String = joinToString(prefix = "{", postfix = "}") { "${it.first}: ${it.second}" }
}

/** Mutable response headers in insertion order with case-insensitive names. */
public class ResponseHeaders internal constructor() {
    private val names = ArrayList<String>(8)
    private val values = ArrayList<String>(8)

    /** The number of header fields. */
    public val size: Int
        get() = names.size

    /** The name of the field at [index]. */
    public fun name(index: Int): String = names[index]

    /** The value of the field at [index]. */
    public fun value(index: Int): String = values[index]

    /** Replaces all values of [name] with [value]. */
    public operator fun set(name: String, value: String) {
        val i = indexOf(name)
        if (i < 0) {
            names.add(name)
            values.add(value)
            return
        }
        names[i] = name
        values[i] = value
        removeFrom(name, i + 1)
    }

    /** Adds a value for [name], keeping existing ones. */
    public fun add(name: String, value: String) {
        names.add(name)
        values.add(value)
    }

    /** Removes all values of [name]. */
    public fun remove(name: String) {
        removeFrom(name, 0)
    }

    /** Removes all headers. */
    public fun clear() {
        names.clear()
        values.clear()
    }

    private fun removeFrom(name: String, start: Int) {
        var i = start
        while (i < names.size) {
            if (names[i].equals(name, ignoreCase = true)) {
                names.removeAt(i)
                values.removeAt(i)
            } else {
                i++
            }
        }
    }

    private fun indexOf(name: String): Int {
        for (i in names.indices) if (names[i].equals(name, ignoreCase = true)) return i
        return -1
    }

    /** Returns the first value of [name], or null. */
    public operator fun get(name: String): String? = indexOf(name).let { if (it < 0) null else values[it] }

    /** Returns all values of [name]. */
    public fun getAll(name: String): List<String> =
        names.indices.filter { names[it].equals(name, ignoreCase = true) }.map { values[it] }

    /** Returns true if [name] is present. */
    public operator fun contains(name: String): Boolean = indexOf(name) >= 0

    /** The distinct header names in insertion order. */
    public fun names(): Set<String> = names.toCollection(LinkedHashSet())

    override fun toString(): String =
        names.indices.joinToString(prefix = "{", postfix = "}") { "${names[it]}: ${values[it]}" }
}
