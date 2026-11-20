package malibu.llm.prompt.api

import io.swagger.v3.core.converter.ModelConverters
import malibu.llm.prompt.config.PatchModelConverter
import malibu.llm.prompt.data.dto.WorkspaceUpdateRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** OpenAPI 문서에서 Patch<T> 가 T(nullable)로 펼쳐지는지. springdoc 없이 swagger-core 로 직접 검사한다. */
class PatchModelConverterTest {

    @Test
    fun `Patch 필드는 내부 타입으로 펼쳐진다`() {
        val converters = ModelConverters().apply { addConverter(PatchModelConverter()) }
        val schemas = converters.readAll(WorkspaceUpdateRequest::class.java)

        assertFalse(schemas.keys.any { it.startsWith("Patch") }, "Patch 스키마가 노출됨: ${schemas.keys}")
        val ws = schemas.getValue("WorkspaceUpdateRequest")
        assertTrue(ws.required.isNullOrEmpty(), "Patch 필드가 required 로 표시됨: ${ws.required}")
        val props = ws.properties

        val description = props.getValue("description")
        assertEquals("string", description.type)
        assertTrue(description.nullable == true)
        assertNull(description.`$ref`)

        val defaultModelId = props.getValue("defaultModelId")
        assertEquals("integer", defaultModelId.type)
        assertEquals("int64", defaultModelId.format)
        assertTrue(defaultModelId.nullable == true)

        assertEquals("string", props.getValue("name").type)
    }
}
