package net.tegulis.kalin

/** Marks APIs that are public only for use by other Kalin modules (for example `kalin-testtools`). */
@RequiresOptIn(message = "Internal Kalin API, may change without notice", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class KalinInternalApi
