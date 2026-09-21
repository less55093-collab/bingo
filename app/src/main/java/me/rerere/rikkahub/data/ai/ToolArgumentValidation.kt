package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.*

/** Validates the JSON-schema constraints used by the App's tool definitions before side effects. */
internal fun validateToolArgumentValue(value: JsonElement, schema: JsonObject, path: String = "参数") {
    val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
    val validType = when (type) {
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
        "null" -> value == JsonNull
        else -> true
    }
    require(validType) { "$path 类型错误，应为 $type" }
    (schema["enum"] as? JsonArray)?.let { allowed -> require(value in allowed) { "$path 不在允许的取值范围内" } }
    if (value is JsonObject) {
        (schema["required"] as? JsonArray)?.forEach { required ->
            val key = required.jsonPrimitive.content
            require(value[key] != null && value[key] != JsonNull) { "$path 缺少 $key" }
        }
        (schema["properties"] as? JsonObject)?.forEach { (key, child) ->
            if (child is JsonObject) value[key]?.let { validateToolArgumentValue(it, child, "$path.$key") }
        }
    }
    if (value is JsonArray) {
        (schema["minItems"] as? JsonPrimitive)?.intOrNull?.let { require(value.size >= it) { "$path 数量不足" } }
        (schema["maxItems"] as? JsonPrimitive)?.intOrNull?.let { require(value.size <= it) { "$path 数量过多" } }
        (schema["items"] as? JsonObject)?.let { child ->
            value.forEachIndexed { index, item -> validateToolArgumentValue(item, child, "$path[$index]") }
        }
    }
}
