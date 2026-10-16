package net.tegulis.kalin.json

import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream
import kotlin.reflect.KType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

/**
 * A [JsonMapper] backed by kotlinx.serialization. Classes must be `@Serializable` (or have a contextual serializer
 * registered in [json]'s serializers module). Values typed as `Any`, `Map<String, Any?>` or `List<Any?>` are converted
 * dynamically (strings, numbers, booleans, maps, lists and `@Serializable` values).
 *
 * Registered automatically when `kalin-json` is on the classpath.
 */
public class KotlinxJsonMapper(
    /** The configured [Json] instance. */
    public val json: Json = DEFAULT_JSON
) : JsonMapper {
    private val byKType = ConcurrentHashMap<KType, KSerializer<Any?>>()
    private val byJavaType = ConcurrentHashMap<Type, KSerializer<Any?>>()
    private val failedTypes = ConcurrentHashMap.newKeySet<Any>()

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: KType): KSerializer<Any?>? {
        byKType[type]?.let {
            return it
        }
        if (type in failedTypes) return null
        return try {
            json.serializersModule.serializer(type).also { byKType[type] = it }
        } catch (_: SerializationException) {
            failedTypes.add(type)
            null
        } catch (_: IllegalArgumentException) {
            failedTypes.add(type)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: Type): KSerializer<Any?>? {
        byJavaType[type]?.let {
            return it
        }
        if (type in failedTypes) return null
        return try {
            (json.serializersModule.serializer(type) as KSerializer<Any?>).also { byJavaType[type] = it }
        } catch (_: SerializationException) {
            failedTypes.add(type)
            null
        } catch (_: IllegalArgumentException) {
            failedTypes.add(type)
            null
        }
    }

    private fun isDynamic(type: KType): Boolean = type.classifier == Any::class

    override fun toJsonString(obj: Any?, type: KType): String {
        if (obj == null) return "null"
        if (!isDynamic(type))
            serializerFor(type)?.let {
                return json.encodeToString(it, obj)
            }
        return json.encodeToString(JsonElement.serializer(), toElement(obj))
    }

    /** Serializers whose recent outputs were large; they use the streaming string encoder. */
    private val largeOutputs = ConcurrentHashMap.newKeySet<KSerializer<*>>()

    override fun toJsonBytes(obj: Any?, type: KType): ByteArray {
        if (obj == null) return NULL_BYTES
        val serializer = if (isDynamic(type)) null else serializerFor(type)
        if (serializer == null) return Utf8JsonWriter.encode(json, JsonElement.serializer(), toElement(obj))
        // Small values: kotlinx's tree encoder needs no shared (synchronized) buffer pool, which scales better across
        // event loop threads. Large values: the streaming encoder is faster and allocates less.
        if (serializer in largeOutputs) {
            val bytes = json.encodeToString(serializer, obj).toByteArray(Charsets.UTF_8)
            if (bytes.size < SMALL_OUTPUT) largeOutputs.remove(serializer)
            return bytes
        }
        val bytes = Utf8JsonWriter.encode(json, serializer, obj)
        if (bytes.size > LARGE_OUTPUT) largeOutputs.add(serializer)
        return bytes
    }

    override fun toJsonString(obj: Any, type: Type): String {
        serializerFor(type)?.let {
            return json.encodeToString(it, obj)
        }
        return json.encodeToString(JsonElement.serializer(), toElement(obj))
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun toJsonStream(obj: Any?, type: KType): InputStream {
        if (obj == null) return "null".byteInputStream()
        val serializer = if (isDynamic(type)) null else serializerFor(type)
        val pipe = java.io.PipedInputStream(64 * 1024)
        val out = java.io.PipedOutputStream(pipe)
        Thread.ofVirtual().start {
            out.use {
                if (serializer != null) json.encodeToStream(serializer, obj, it)
                else json.encodeToStream(JsonElement.serializer(), toElement(obj), it)
            }
        }
        return pipe
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun writeToOutputStream(stream: Stream<*>, outputStream: OutputStream) {
        outputStream.write('['.code)
        var first = true
        stream.use { s ->
            for (element in s) {
                if (!first) outputStream.write(','.code)
                first = false
                if (element == null) {
                    outputStream.write("null".toByteArray())
                    continue
                }
                val serializer = serializerFor(element.javaClass)
                if (serializer != null) json.encodeToStream(serializer, element, outputStream)
                else json.encodeToStream(JsonElement.serializer(), toElement(element), outputStream)
            }
        }
        outputStream.write(']'.code)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> fromJsonString(json: String, targetType: KType): T {
        if (isDynamic(targetType)) return fromElement(this.json.parseToJsonElement(json)) as T
        val serializer = serializerFor(targetType) ?: return dynamicOrFail(json, targetType.toString())
        return this.json.decodeFromString(serializer, json) as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> fromJsonString(json: String, targetType: Type): T {
        if (targetType == Any::class.java) return fromElement(this.json.parseToJsonElement(json)) as T
        val serializer = serializerFor(targetType) ?: return dynamicOrFail(json, targetType.typeName)
        return this.json.decodeFromString(serializer, json) as T
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    override fun <T> fromJsonStream(json: InputStream, targetType: KType): T {
        if (isDynamic(targetType)) return fromElement(this.json.decodeFromStream(JsonElement.serializer(), json)) as T
        val serializer =
            serializerFor(targetType)
                ?: return dynamicOrFail(json.readAllBytes().decodeToString(), targetType.toString())
        return this.json.decodeFromStream(serializer, json) as T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> fromJsonStream(json: InputStream, targetType: Type): T =
        fromJsonString(json.readAllBytes().decodeToString(), targetType)

    @Suppress("UNCHECKED_CAST")
    private fun <T> dynamicOrFail(text: String, typeName: String): T {
        val generic =
            typeName.startsWith("kotlin.collections.Map") ||
                typeName.startsWith("kotlin.collections.List") ||
                typeName.startsWith("java.util.Map") ||
                typeName.startsWith("java.util.List")
        if (generic) return fromElement(json.parseToJsonElement(text)) as T
        throw SerializationException(
            "No kotlinx.serialization serializer found for $typeName. Annotate the class with @Serializable " +
                "(and apply the kotlin(\"plugin.serialization\") Gradle plugin) or register a contextual serializer."
        )
    }

    /** Converts a plain value to a [JsonElement]. */
    public fun toElement(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Enum<*> -> JsonPrimitive(value.name)
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toElement(v) })
            is Iterable<*> -> JsonArray(value.map { toElement(it) })
            is Array<*> -> JsonArray(value.map { toElement(it) })
            is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
            is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
            is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
            is BooleanArray -> JsonArray(value.map { JsonPrimitive(it) })
            is Sequence<*> -> JsonArray(value.map { toElement(it) }.toList())
            is net.tegulis.kalin.validation.ValidationError<*> ->
                JsonObject(
                    mapOf(
                        "message" to JsonPrimitive(value.message),
                        "args" to toElement(value.args),
                        "value" to toElement(value.value),
                    )
                )
            else -> {
                val serializer = serializerFor(value.javaClass)
                if (serializer != null) json.encodeToJsonElement(serializer, value) else JsonPrimitive(value.toString())
            }
        }

    /** Converts a [JsonElement] to plain Kotlin values (maps, lists, strings, numbers, booleans, null). */
    public fun fromElement(element: JsonElement): Any? =
        when (element) {
            JsonNull -> null
            is JsonObject -> element.mapValuesTo(LinkedHashMap()) { fromElement(it.value) }
            is JsonArray -> element.map { fromElement(it) }
            is JsonPrimitive ->
                when {
                    element.isString -> element.content
                    element.booleanOrNull != null -> element.booleanOrNull
                    element.longOrNull != null ->
                        element.longOrNull!!.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                    else -> element.content.toDouble()
                }
        }

    public companion object {
        private val NULL_BYTES = "null".toByteArray()
        private const val SMALL_OUTPUT = 512
        private const val LARGE_OUTPUT = 2048

        /** The default [Json]: ignores unknown keys, encodes defaults. */
        @JvmField
        public val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** Registers [KotlinxJsonMapper] as the default mapper through `ServiceLoader`. */
public class KotlinxJsonMapperProvider : JsonMapperProvider {
    override fun create(): JsonMapper = KotlinxJsonMapper()
}
