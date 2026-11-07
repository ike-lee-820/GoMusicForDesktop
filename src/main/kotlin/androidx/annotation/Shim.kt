package androidx.annotation

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
    AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE, AnnotationTarget.EXPRESSION,
    AnnotationTarget.FILE, AnnotationTarget.TYPEALIAS)
@Retention(AnnotationRetention.BINARY)
annotation class OptIn(vararg val markerClass: KClass<*>)

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class RequiresOptIn(val message: String = "", val level: Level = Level.ERROR) {
    enum class Level { WARNING, ERROR }
}
