package malibu.llm.streamclient.schema

object OpenAiToolSchemaNormalizer {
    fun normalize(schema: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return normalizeNode(schema) as Map<String, Any?>
    }

    private fun normalizeNode(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> normalizeMap(value)
            is List<*> -> value.map(::normalizeNode)
            else -> value
        }
    }

    private fun normalizeMap(value: Map<*, *>): Map<String, Any?> {
        val normalized = linkedMapOf<String, Any?>()
        value.forEach { (key, raw) ->
            if (key is String) {
                normalized[key] = normalizeNode(raw)
            }
        }

        val isNullable = normalized.remove("nullable") == true
        val type = normalized["type"]
        if (isNullable && type is String) {
            normalized["type"] = listOf(type, "null")
        }

        @Suppress("UNCHECKED_CAST")
        val properties = normalized["properties"] as? Map<String, Any?>
        if (properties != null) {
            normalized["required"] = properties.keys.toList()
        }

        return normalized
    }
}
