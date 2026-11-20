package malibu.llm.prompt.api

import malibu.llm.prompt.authz.LiquibasePostgres
import malibu.llm.prompt.authz.MembershipFixture
import malibu.llm.prompt.authz.Roles
import malibu.llm.prompt.data.repo.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * PATCH 의 Patch<T> 필드: 키 없음 = 변경 없음, null = 지우기, 값 = 변경.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatchRequestHttpTest(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    jdbcTemplate: JdbcTemplate,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("patch_request_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)
    }

    private val json = JsonMapper()
    private val fixture = MembershipFixture(jdbcTemplate)

    private lateinit var admin: String
    private lateinit var owner: String
    private lateinit var member: String
    private lateinit var wsId: UUID
    private var modelId: Long = 0

    private data class Res(val status: Int, val body: JsonNode?)

    private fun call(method: HttpMethod, token: String?, path: String, body: Any? = null): Res {
        val builder = MockMvcRequestBuilders.request(method, path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        }
        val text = mockMvc.perform(builder).andReturn().response.let { it.status to it.contentAsString }
        return Res(text.first, text.second.takeIf { it.isNotBlank() }?.let { json.readTree(it) })
    }

    private fun get(token: String?, path: String) = call(HttpMethod.GET, token, path)
    private fun post(token: String?, path: String, body: Any) = call(HttpMethod.POST, token, path, body)
    private fun patch(token: String?, path: String, body: Any) = call(HttpMethod.PATCH, token, path, body)

    private fun tokenOf(email: String): String =
        post(null, "/dev/token", mapOf("email" to email, "name" to email.substringBefore("@"))).body!!
            .get("accessToken").asText()

    private fun ok(res: Res): JsonNode {
        assertEquals(200, res.status, "body: ${res.body}")
        return res.body!!
    }

    private fun JsonNode.isNullAt(field: String) = get(field) == null || get(field).isNull

    @BeforeAll
    fun setUp() {
        admin = tokenOf("admin@test.local")
        owner = tokenOf("owner@test.local")
        member = tokenOf("member@test.local")
        val ownerUser = userRepository.findByEmail("owner@test.local")!!
        val orgId = fixture.personalOrganizationId(ownerUser)
        wsId = fixture.defaultWorkspaceId(ownerUser)
        val memberUser = userRepository.findByEmail("member@test.local")!!
        fixture.addOrgMember(orgId, memberUser, Roles.ORG_MEMBER)
        fixture.addWorkspaceMember(wsId, orgId, memberUser, Roles.WORKSPACE_MEMBER)

        val vendorId = post(admin, "/llm-vendors", mapOf("name" to "patch-vendor")).body!!.get("id").asText()
        modelId = post(
            admin, "/models",
            mapOf("vendorId" to vendorId, "name" to "m", "modelKey" to "m-key", "contextWindow" to 1000, "inputPrice" to 5),
        ).body!!.get("id").asLong()
    }

    @Test
    fun `workspace - 키 없음은 유지, null 은 지우기`() {
        val path = "/workspaces/$wsId"
        ok(patch(owner, path, mapOf("description" to "desc", "defaultModelId" to modelId)))

        // 다른 필드만 보내면 description / defaultModelId 는 그대로
        val kept = ok(patch(owner, path, mapOf("name" to "renamed")))
        assertEquals("desc", kept.get("description").asText())
        assertEquals(modelId, kept.get("defaultModelId").asLong())

        // null 을 보내면 지워진다
        val cleared = ok(patch(owner, path, mapOf("description" to null, "defaultModelId" to null)))
        assertTrue(cleared.isNullAt("description"))
        assertTrue(cleared.isNullAt("defaultModelId"))
        assertEquals("renamed", cleared.get("name").asText())

        // 필수 필드 name 은 null 이어도 바뀌지 않는다
        assertEquals("renamed", ok(patch(owner, path, mapOf("name" to null))).get("name").asText())
    }

    @Test
    fun `model - 숫자 필드도 null 로 지운다`() {
        val path = "/models/$modelId"
        val kept = ok(patch(admin, path, mapOf("description" to "d")))
        assertEquals(1000, kept.get("contextWindow").asLong())

        val cleared = ok(patch(admin, path, mapOf("contextWindow" to null, "inputPrice" to null)))
        assertTrue(cleared.isNullAt("contextWindow"))
        assertTrue(cleared.isNullAt("inputPrice"))
        assertEquals("d", cleared.get("description").asText())
    }

    @Test
    fun `prompt - latestTag 해제는 publish 권한이 필요하다`() {
        val base = "/workspaces/$wsId/prompts"
        val created = post(member, base, mapOf("name" to "patch-p", "instructions" to "hi")).body!!
        val promptId = created.get("id").asText()
        val mainRefId = created.get("defaultBranchRefId").asLong()
        post(owner, "$base/$promptId/refs", mapOf("name" to "v1", "type" to "TAG", "fromRefId" to mainRefId))
        assertFalse(ok(get(owner, "$base/$promptId")).isNullAt("latestTagRefId"))

        // latestTagRefId 를 보내지 않으면 publish 권한이 없어도 된다
        ok(patch(member, "$base/$promptId", mapOf("description" to "x")))
        // null 로 해제하는 것도 publish 다
        assertEquals(403, patch(member, "$base/$promptId", mapOf("latestTagRefId" to null)).status)
        val cleared = ok(patch(owner, "$base/$promptId", mapOf("latestTagRefId" to null)))
        assertTrue(cleared.isNullAt("latestTagRefId"))
        assertEquals("x", cleared.get("description").asText())
        assertEquals(mainRefId, cleared.get("defaultBranchRefId").asLong())

        // ref description
        val refPath = "$base/$promptId/refs/$mainRefId"
        assertEquals("rd", ok(patch(member, refPath, mapOf("description" to "rd"))).get("description").asText())
        assertTrue(ok(patch(member, refPath, mapOf("description" to null))).isNullAt("description"))
    }

    @Test
    fun `api-docs - Patch 필드는 선택적이고 nullable 인 내부 타입으로 문서화된다`() {
        val res = get(null, "/api-docs")
        assertEquals(200, res.status)
        val schemas = res.body!!.get("components").get("schemas")
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
