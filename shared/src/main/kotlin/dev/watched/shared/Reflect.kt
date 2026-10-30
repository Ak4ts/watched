package dev.watched.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * Turns an arbitrary SDK object into JSON by walking its properties.
 *
 * Phase 0 exists precisely because neither Samsung nor Google documents the field-level schema
 * of these records. Hand-writing serialisers would mean deciding in advance what the fields are,
 * which is the question we are trying to answer. So we reflect, dump everything, and read the
 * real shape back out of the dumps.
 *
 * This is diagnostic code. It is slow and it is not intended to survive into the collector.
 */
fun reflectToJson(value: Any?, depth: Int = 0): JsonElement {
    if (value == null) return JsonNull
    if (depth > MAX_DEPTH) return JsonPrimitive("<max-depth:${value::class.simpleName}>")

    return when (value) {
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is CharSequence -> JsonPrimitive(value.toString())
        is Enum<*> -> JsonPrimitive(value.name)

        is Iterable<*> -> JsonArray(value.map { reflectToJson(it, depth + 1) })
        is Array<*> -> JsonArray(value.map { reflectToJson(it, depth + 1) })
        // Raw sensor payloads arrive as primitive arrays; keep them numeric, not stringified.
        is IntArray -> JsonArray(value.map { JsonPrimitive(it) })
        is LongArray -> JsonArray(value.map { JsonPrimitive(it) })
        is FloatArray -> JsonArray(value.map { JsonPrimitive(it) })
        is DoubleArray -> JsonArray(value.map { JsonPrimitive(it) })
        is ByteArray -> JsonPrimitive("<bytes:${value.size}>")

        is Map<*, *> -> JsonObject(
            value.entries.associate { (k, v) -> k.toString() to reflectToJson(v, depth + 1) }
        )

        else -> reflectObject(value, depth)
    }
}

private const val MAX_DEPTH = 6

private fun reflectObject(value: Any, depth: Int): JsonElement {
    val klass = value::class

    // java.time types and anything else opaque: toString() is more useful than its internals.
    val pkg = klass.java.`package`?.name.orEmpty()
    if (pkg.startsWith("java.") || pkg.startsWith("kotlin.")) {
        return JsonPrimitive(value.toString())
    }

    return try {
        val props = klass.memberProperties
        if (props.isEmpty()) return JsonPrimitive(value.toString())
        JsonObject(
            props.associate { prop ->
                prop.isAccessible = true
                val v = try {
                    prop.getter.call(value)
                } catch (t: Throwable) {
                    "<error:${t::class.simpleName}>"
                }
                prop.name to reflectToJson(v, depth + 1)
            }
        )
    } catch (t: Throwable) {
        // Obfuscated or reflection-hostile SDK classes land here. Record that, don't crash the sweep.
        JsonPrimitive("<unreflectable:${klass.simpleName}:${t::class.simpleName}>")
    }
}

/** Which top-level keys were present and non-null, for [TypeReport.fieldPresence]. */
fun countFieldPresence(records: List<JsonElement>): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()
    for (r in records) {
        if (r !is JsonObject) continue
        for ((k, v) in r) {
            if (v !is JsonNull) counts[k] = (counts[k] ?: 0) + 1
        }
    }
    return counts.toSortedMap()
}
