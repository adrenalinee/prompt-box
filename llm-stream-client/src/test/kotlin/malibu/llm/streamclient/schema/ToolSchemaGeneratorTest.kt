package malibu.llm.streamclient.schema

import malibu.llm.streamclient.ToolParam
import malibu.llm.streamclient.schema.ToolSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolSchemaGeneratorTest {
    private val generator = ToolSchemaGenerator()

    @Test
    fun `generate should include descriptions and nullable optional fields`() {
        val schema = generator.generate(SearchInput::class)

        assertEquals("object", schema["type"])
        assertEquals(false, schema["additionalProperties"])

        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Map<String, Any?>>
        @Suppress("UNCHECKED_CAST")
        val required = schema["required"] as List<String>

        assertEquals("검색어", properties.getValue("query")["description"])
        assertEquals("string", properties.getValue("query")["type"])
        assertEquals("카테고리. ALL: 전체, NEWS: 뉴스", properties.getValue("category")["description"])
        assertEquals(listOf("ALL", "NEWS"), properties.getValue("category")["enum"])
        assertEquals(true, properties.getValue("limit")["nullable"])
        assertEquals("object", properties.getValue("filter")["type"])

        assertTrue(required.contains("query"))
        assertTrue(required.contains("category"))
        assertTrue(required.contains("filter"))
        assertFalse(required.contains("limit"))
    }

    private data class SearchInput(
        @ToolParam("검색어")
        val query: String,
        @ToolParam("카테고리. ALL: 전체, NEWS: 뉴스")
        val category: SearchCategory,
        @ToolParam("최대 개수")
        val limit: Int?,
        val filter: SearchFilter,
    )

    private enum class SearchCategory {
        ALL,
        NEWS,
    }

    private data class SearchFilter(
        @ToolParam("지역 코드")
        val region: String,
    )
}
