package malibu.llm.prompt.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * OpenAPI 문서(`/api-docs`). local 프로필이라 /dev 엔드포인트도 문서에 포함된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiDocsHttpTest(
    private val mockMvc: MockMvc,
) {
    private lateinit var docs: JsonNode

    @BeforeAll
    fun load() {
        val response = mockMvc.perform(MockMvcRequestBuilders.get("/api-docs")).andReturn().response
        assertEquals(200, response.status)
        docs = JsonMapper().readTree(response.contentAsString)
    }

    private val schemas get() = docs.get("components").get("schemas")

    @Test
    fun `API 소개가 있다`() {
        val info = docs.get("info")
        assertEquals("PromptBox API", info.get("title").asText())
        assertTrue(info.get("description").asText().contains("## PATCH 규칙"))
    }

    @Test
    fun `tag 는 정한 순서로 나온다`() {
        val names = docs.get("tags").map { it.get("name").asText() }
        assertEquals(listOf("상태", "내 정보", "권한 카탈로그", "Organization", "Workspace"), names.take(5))
        assertEquals("개발용 (local)", names.last())
    }

    @Test
    fun `모든 operation 에 summary 와 설명이 있는 tag 가 있다`() {
        val tagDescriptions = docs.get("tags").associate { it.get("name").asText() to it.get("description")?.asText() }
        val missing = mutableListOf<String>()
        for ((path, item) in docs.get("paths").properties()) {
            for ((method, op) in item.properties()) {
                val where = "${method.uppercase()} $path"
                if (op.get("summary")?.asText().isNullOrBlank()) missing += "$where: summary 없음"
                val tags = op.get("tags")?.map { it.asText() }.orEmpty()
                if (tags.isEmpty() || tags.any { tagDescriptions[it].isNullOrBlank() }) missing += "$where: tag 설명 없음 $tags"
            }
        }
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }

    @Test
    fun `PATCH 필드에 null 의미가 적혀 있다`() {
        val ws = schemas.get("WorkspaceUpdateRequest").get("properties")
        assertTrue(ws.get("description").get("description").asText().contains("null 이면 지운다"), ws.toString())
        assertTrue(ws.get("name").get("description").asText().contains("변경 없음"), ws.toString())
        assertTrue(ws.get("defaultModelId").get("description").asText().contains("해제"), ws.toString())

        val prompt = schemas.get("PromptUpdateRequest").get("properties")
        assertTrue(prompt.get("latestTagRefId").get("description").asText().contains("prompt_ref:publish"), prompt.toString())
    }

    @Test
    fun `Patch 필드는 선택적이고 nullable 인 내부 타입으로 문서화된다`() {
        assertFalse(schemas.propertyNames().any { it.startsWith("Patch") }, "Patch 스키마가 노출됨")

        val ws = schemas.get("WorkspaceUpdateRequest")
        assertTrue(ws.get("required") == null, "Patch 필드가 required 로 표시됨: $ws")
        // OAS 3.1: type 배열에 "null"
        val types = { field: String -> ws.get("properties").get(field).get("type").map { it.asText() }.toSet() }
        assertEquals(setOf("string", "null"), types("description"))
        assertEquals(setOf("integer", "null"), types("defaultModelId"))
        assertEquals("int64", ws.get("properties").get("defaultModelId").get("format").asText())
    }
}
