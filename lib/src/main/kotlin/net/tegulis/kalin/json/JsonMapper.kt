package net.tegulis.kalin.json

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.util.ServiceLoader
import java.util.stream.Stream
import kotlin.reflect.KType
import kotlin.reflect.javaType

/**
 * Converts objects to and from JSON. Kotlin callers pass a [KType] (from `typeOf<T>()`), which lets mappers such as
 * kotlinx.serialization resolve generic types without reflection. The [Type] variants exist for Javalin compatibility.
 *
 * `kalin-json` provides `KotlinxJsonMapper`, registered automatically when the module is on the classpath.
 */
public interface JsonMapper {
    /** Serializes [obj] of Java type [type]. */
    public fun toJsonString(obj: Any, type: Type): String =
        throw UnsupportedOperationException("${javaClass.simpleName}.toJsonString(Any, Type) is not implemented")

    /** Serializes [obj] of Kotlin type [type]. */
    @OptIn(ExperimentalStdlibApi::class)
    public fun toJsonString(obj: Any?, type: KType): String =
        if (obj == null) "null" else toJsonString(obj, type.javaType)

    /** Serializes [obj] to a stream. */
    public fun toJsonStream(obj: Any, type: Type): InputStream =
        ByteArrayInputStream(toJsonString(obj, type).toByteArray())

    /** Serializes [obj] to a stream. */
    public fun toJsonStream(obj: Any?, type: KType): InputStream =
        ByteArrayInputStream(toJsonString(obj, type).toByteArray())

    /** Writes the elements of [stream] as a JSON array to [outputStream]. */
    public fun writeToOutputStream(stream: Stream<*>, outputStream: OutputStream) {
        outputStream.write('['.code)
        var first = true
        stream.use { s ->
            for (element in s) {
                if (!first) outputStream.write(','.code)
                first = false
                outputStream.write(
                    (if (element == null) "null" else toJsonString(element, element.javaClass as Type)).toByteArray()
                )
            }
        }
        outputStream.write(']'.code)
    }

    /** Deserializes [json] to the Java type [targetType]. */
    public fun <T> fromJsonString(json: String, targetType: Type): T =
        throw UnsupportedOperationException("${javaClass.simpleName}.fromJsonString(String, Type) is not implemented")

    /** Deserializes [json] to the Kotlin type [targetType]. */
    @OptIn(ExperimentalStdlibApi::class)
    public fun <T> fromJsonString(json: String, targetType: KType): T = fromJsonString(json, targetType.javaType)

    /** Deserializes a JSON stream to the Java type [targetType]. */
    public fun <T> fromJsonStream(json: InputStream, targetType: Type): T =
        fromJsonString(json.readAllBytes().decodeToString(), targetType)

    /** Deserializes a JSON stream to the Kotlin type [targetType]. */
    public fun <T> fromJsonStream(json: InputStream, targetType: KType): T =
        fromJsonString(json.readAllBytes().decodeToString(), targetType)

    public companion object {
        /** Finds a mapper through [JsonMapperProvider] services, or returns the built-in [SimpleJsonMapper]. */
        @JvmStatic
        public fun default(): JsonMapper =
            ServiceLoader.load(JsonMapperProvider::class.java).firstOrNull()?.create() ?: SimpleJsonMapper
    }
}

/** Serializes with the static type [T]. */
public inline fun <reified T> JsonMapper.toJsonString(obj: T): String = toJsonString(obj, kotlin.reflect.typeOf<T>())

/** Deserializes to [T]. */
public inline fun <reified T> JsonMapper.fromJsonString(json: String): T =
    fromJsonString(json, kotlin.reflect.typeOf<T>())

/** Service interface for automatic JSON mapper registration (`META-INF/services`). */
public interface JsonMapperProvider {
    /** Creates the mapper. */
    public fun create(): JsonMapper
}

/**
 * The fallback mapper when no JSON module is present. It writes maps, collections, arrays, strings, numbers, booleans
 * and null, and reads JSON into those types. Add `kalin-json` for data classes.
 */
public object SimpleJsonMapper : JsonMapper {
    override fun toJsonString(obj: Any, type: Type): String = SimpleJson.write(obj)

    override fun toJsonString(obj: Any?, type: KType): String = SimpleJson.write(obj)

    @Suppress("UNCHECKED_CAST")
    override fun <T> fromJsonString(json: String, targetType: Type): T =
        checked(SimpleJson.parse(json), targetType.typeName) as T

    @Suppress("UNCHECKED_CAST")
    override fun <T> fromJsonString(json: String, targetType: KType): T =
        checked(SimpleJson.parse(json), targetType.toString()) as T

    private fun checked(value: Any?, typeName: String): Any? {
        val ok =
            typeName.startsWith("java.util.Map") ||
                typeName.startsWith("kotlin.collections.Map") ||
                typeName.startsWith("java.util.List") ||
                typeName.startsWith("kotlin.collections.List") ||
                typeName.startsWith("kotlin.Any") ||
                typeName == "java.lang.Object" ||
                typeName.startsWith("kotlin.String") ||
                typeName == "java.lang.String"
        if (!ok) {
            throw UnsupportedOperationException(
                "No JSON mapper for $typeName: add the kalin-json module (kotlinx.serialization) or set config.jsonMapper(...)"
            )
        }
        return value
    }
}
