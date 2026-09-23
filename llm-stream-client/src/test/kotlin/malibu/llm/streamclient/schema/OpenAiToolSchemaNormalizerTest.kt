package malibu.llm.streamclient.schema

import malibu.llm.streamclient.schema.OpenAiToolSchemaNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class OpenAiToolSchemaNormalizerTest {
    @Test
    fun `normalize should require all properties and convert nullable to null union`() {
        val normalized = OpenAiToolSchemaNormalizer.normalize(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "userId" to mapOf("type" to "integer"),
                    "targetDate" to mapOf(
                        "type" to "string",
                        "nullable" to true,
                    ),
                ),
                "required" to listOf("userId"),
                "additionalProperties" to false,
            )
        )

        @Suppress("UNCHECKED_CAST")
        val properties = normalized["properties"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val required = normalized["required"] as List<String>

        assertEquals(listOf("userId", "targetDate"), required)
        assertEquals(listOf("string", "null"), properties.getValue("targetDate")["type"])
        assertFalse(properties.getValue("targetDate").containsKey("nullable"))
    }
}
