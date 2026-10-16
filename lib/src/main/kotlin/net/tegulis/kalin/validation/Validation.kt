package net.tegulis.kalin.validation

import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID
import java.util.function.Function
import net.tegulis.kalin.http.KalinException

/** A check on a validated value. */
public typealias Check<T> = (T) -> Boolean

/** A validation error with an optional localization payload. */
public data class ValidationError<out T>
@JvmOverloads
constructor(
    /** The error message or key, for example `TYPE_CONVERSION_FAILED`. */
    val message: String,
    /** Arguments for localization. */
    val args: Map<String, Any?> = emptyMap(),
    /** The rejected value. */
    var value: Any? = null,
    private val exception: Exception? = null,
) {
    /** The exception that caused the error, if any. */
    public fun exception(): Exception? = exception
}

/** Thrown by [Validator.get] when validation fails; mapped to `400` with the errors as JSON. */
public class ValidationException(
    /** Errors by field name. */
    public val errors: Map<String, List<ValidationError<Any>>>
) : KalinException("Validation failed")

/** Thrown when no converter is registered for a validated type. */
public class MissingConverterException(
    /** The class without converter. */
    public val className: String
) : KalinException("No converter registered for class: $className")

/** Converter registry, configured through `config.validation`. */
public class ValidationConfig {
    internal val converters: MutableMap<Class<*>, (String) -> Any?> = LinkedHashMap()

    init {
        fun <T : Any> both(primitive: Class<*>?, boxed: Class<T>, f: (String) -> T) {
            converters[boxed] = f
            if (primitive != null) converters[primitive] = f
        }
        both(null, String::class.java) { it }
        both(Int::class.javaPrimitiveType, Int::class.javaObjectType) { it.trim().toInt() }
        both(Long::class.javaPrimitiveType, Long::class.javaObjectType) { it.trim().toLong() }
        both(Double::class.javaPrimitiveType, Double::class.javaObjectType) { it.trim().toDouble() }
        both(Float::class.javaPrimitiveType, Float::class.javaObjectType) { it.trim().toFloat() }
        both(Short::class.javaPrimitiveType, Short::class.javaObjectType) { it.trim().toShort() }
        both(Byte::class.javaPrimitiveType, Byte::class.javaObjectType) { it.trim().toByte() }
        both(Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType) {
            when (it.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("Not a boolean: $it")
            }
        }
        both(Char::class.javaPrimitiveType, Char::class.javaObjectType) {
            it.singleOrNull() ?: throw IllegalArgumentException("Not a char: $it")
        }
        both(null, BigDecimal::class.java) { BigDecimal(it.trim()) }
        both(null, BigInteger::class.java) { BigInteger(it.trim()) }
        both(null, UUID::class.java) { UUID.fromString(it.trim()) }
    }

    /** Registers a converter from string to [clazz]. */
    public fun <T> register(clazz: Class<T>, converter: Function<String, T>) {
        converters[clazz] = { converter.apply(it) }
    }

    /** Registers a converter from string to [T]. */
    public inline fun <reified T : Any> register(noinline converter: (String) -> T) {
        register(T::class.java, Function { converter(it) })
    }
}

/** Creates validators using the registered converters. */
public class Validation(private val config: ValidationConfig = ValidationConfig()) {
    @Suppress("UNCHECKED_CAST")
    private fun <T> converter(clazz: Class<T>): (String) -> T {
        config.converters[clazz]?.let {
            return it as (String) -> T
        }
        if (clazz.isEnum) {
            val constants = clazz.enumConstants
            return { s ->
                constants.firstOrNull { (it as Enum<*>).name == s }
                    ?: throw IllegalArgumentException("Unknown constant $s")
            }
        }
        throw MissingConverterException(clazz.name)
    }

    /** A validator converting [value] to [clazz]. */
    public fun <T : Any> validator(fieldName: String, clazz: Class<T>, value: String?): Validator<T> {
        val convert = converter(clazz)
        return Validator(fieldName, value) { if (value == null) null else convert(value) }
    }

    /** A validator for all [values], each converted to [clazz]. */
    public fun <T : Any> listValidator(fieldName: String, clazz: Class<T>, values: List<String>): Validator<List<T>> {
        val convert = converter(clazz)
        return Validator(fieldName, values.joinToString(",")) { values.map { convert(it) } }
    }

    /** A validator for an already typed value. */
    public fun <T : Any> validator(fieldName: String, typedValue: T?): Validator<T> =
        Validator(fieldName, typedValue?.toString()) { typedValue }

    public companion object {
        /** Merges the errors of several validators. */
        @JvmStatic
        public fun collectErrors(vararg validators: Validator<*>): Map<String, List<ValidationError<Any?>>> =
            collectErrors(validators.toList())

        /** Merges the errors of several validators. */
        @JvmStatic
        public fun collectErrors(validators: Iterable<Validator<*>>): Map<String, List<ValidationError<Any?>>> {
            val result = LinkedHashMap<String, MutableList<ValidationError<Any?>>>()
            for (v in validators) for ((k, list) in v.errors()) result.getOrPut(k) { ArrayList() }.addAll(list)
            return result
        }
    }
}

/** Merges the errors of several validators. */
public fun Iterable<Validator<*>>.collectErrors(): Map<String, List<ValidationError<Any?>>> =
    Validation.collectErrors(this)

/**
 * Validates a (converted) request value. Add checks with [check], then call [get], [getOrNull], [getOrDefault] or
 * [getOrThrow]. Errors are computed once, lazily.
 */
public open class Validator<T>
@PublishedApi
internal constructor(
    /** The field name used as error key. */
    @PublishedApi internal val fieldName: String,
    /** The raw string value. */
    @PublishedApi internal val stringValue: String?,
    @PublishedApi internal val valueSupplier: () -> Any?,
) {
    private class Rule(val fieldName: String, val check: Check<Any?>, val error: ValidationError<Any?>)

    private val rules = ArrayList<Rule>()
    private var typedValue: Any? = null
    private var computed: Map<String, List<ValidationError<Any?>>>? = null

    /** The error message used when conversion fails. */
    protected open val conversionErrorMessage: String = "TYPE_CONVERSION_FAILED"

    private fun computeErrors(): Map<String, List<ValidationError<Any?>>> {
        computed?.let {
            return it
        }
        val result: Map<String, List<ValidationError<Any?>>> =
            try {
                typedValue = valueSupplier()
                val errors = LinkedHashMap<String, MutableList<ValidationError<Any?>>>()
                if (typedValue != null) {
                    for (rule in rules) {
                        if (!rule.check(typedValue)) {
                            errors.getOrPut(rule.fieldName) { ArrayList() }.add(rule.error.copy(value = typedValue))
                        }
                    }
                }
                errors
            } catch (e: Exception) {
                if (e is ValidationException) throw e
                mapOf(fieldName to listOf(ValidationError(conversionErrorMessage, value = stringValue, exception = e)))
            }
        computed = result
        return result
    }

    @Suppress("UNCHECKED_CAST")
    internal fun addCheck(fieldName: String, check: Check<T>, error: ValidationError<Any?>) {
        check(computed == null) { "Cannot add checks after the value was validated" }
        rules.add(Rule(fieldName, check as Check<Any?>, error))
    }

    /** All errors by field name (empty if valid). */
    @Suppress("UNCHECKED_CAST")
    public fun errors(): Map<String, List<ValidationError<T>>> =
        computeErrors() as Map<String, List<ValidationError<T>>>

    /** True if a value is present. */
    public fun hasValue(): Boolean = !stringValue.isNullOrEmpty() || typedValue != null

    /** Adds a check with an error message. */
    public open fun check(check: Check<T>, error: String): Validator<T> = apply {
        addCheck(fieldName, check, ValidationError<T>(error))
    }

    /** Adds a check with a [ValidationError]. */
    public open fun check(check: Check<T>, error: ValidationError<Any?>): Validator<T> = apply {
        addCheck(fieldName, check, error)
    }

    /** Javalin compatibility: marks the value as required (the default for [get]). */
    public open fun required(): Validator<T> = this

    /** Returns a validator whose [NullableValidator.get] returns null for a missing value. */
    public fun allowNullable(): NullableValidator<T> = NullableValidator(this)

    private fun throwIfErrors() {
        val errors = computeErrors()
        @Suppress("UNCHECKED_CAST")
        if (errors.isNotEmpty()) throw ValidationException(errors as Map<String, List<ValidationError<Any>>>)
    }

    /** Returns the value or throws [ValidationException] (also when the value is missing). */
    @Suppress("UNCHECKED_CAST")
    public fun get(): T & Any {
        throwIfErrors()
        return typedValue as? (T & Any)
            ?: throw ValidationException(
                mapOf(fieldName to listOf(ValidationError("NULLCHECK_FAILED", value = stringValue)))
            )
    }

    /** Returns the value, null if missing, or throws [ValidationException] if invalid. */
    @Suppress("UNCHECKED_CAST")
    public fun getOrNull(): T? {
        throwIfErrors()
        return typedValue as T?
    }

    /** Returns the value, [default] if missing, or throws [ValidationException] if invalid. */
    public fun getOrDefault(default: T & Any): T & Any = getOrNull() ?: default

    /** Returns the value or throws the exception created by [exceptionFunction]. */
    @Suppress("UNCHECKED_CAST")
    public fun getOrThrow(exceptionFunction: (Map<String, List<ValidationError<Any>>>) -> Exception): T & Any {
        val errors = computeErrors()
        if (errors.isNotEmpty()) throw exceptionFunction(errors as Map<String, List<ValidationError<Any>>>)
        return typedValue as? (T & Any)
            ?: throw exceptionFunction(
                mapOf(fieldName to listOf(ValidationError("NULLCHECK_FAILED", value = stringValue)))
            )
    }
}

/** A validator whose [get] allows a missing value. */
public class NullableValidator<T> internal constructor(private val delegate: Validator<T>) {
    /** Adds a check; it only runs when a value is present. */
    public fun check(check: Check<T>, error: String): NullableValidator<T> = apply { delegate.check(check, error) }

    /** Adds a check; it only runs when a value is present. */
    public fun check(check: Check<T>, error: ValidationError<Any?>): NullableValidator<T> = apply {
        delegate.check(check, error)
    }

    /** Returns the value or null; throws [ValidationException] if invalid. */
    public fun get(): T? = delegate.getOrNull()

    /** All errors by field name. */
    public fun errors(): Map<String, List<ValidationError<T>>> = delegate.errors()
}

/** The field name used for body validation errors. */
public const val REQUEST_BODY: String = "REQUEST_BODY"

/** A validator for a deserialized request body. */
public class BodyValidator<T>(body: String, valueSupplier: () -> Any?) :
    Validator<T>(REQUEST_BODY, body, valueSupplier) {
    override val conversionErrorMessage: String = "DESERIALIZATION_FAILED"

    override fun check(check: Check<T>, error: String): BodyValidator<T> = apply {
        addCheck(REQUEST_BODY, check, ValidationError<T>(error))
    }

    override fun check(check: Check<T>, error: ValidationError<Any?>): BodyValidator<T> = apply {
        addCheck(REQUEST_BODY, check, error)
    }

    /** Adds a check reported under [fieldName]. */
    public fun check(fieldName: String, check: Check<T>, error: String): BodyValidator<T> = apply {
        addCheck(fieldName, check, ValidationError<T>(error))
    }

    /** Adds a check reported under [fieldName]. */
    public fun check(fieldName: String, check: Check<T>, error: ValidationError<Any?>): BodyValidator<T> = apply {
        addCheck(fieldName, check, error)
    }

    override fun required(): BodyValidator<T> = this
}
