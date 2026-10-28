package malibu.llm.prompt.api

import malibu.llm.prompt.TestcontainersConfig
import malibu.llm.prompt.data.PlatformRole
import malibu.llm.prompt.data.repo.AppUserRepository
import malibu.llm.prompt.security.AppUserJwtAuthenticationConverter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * 롤 기반 권한 통합 테스트.
 * - 실제 JWT 디코딩 대신 AppUserJwtAuthenticationConverter 로 만든 인증을 주입해서 가입/롤 부여 로직까지 함께 검증한다.
 * - 다른 테스트와 DB 를 공유하므로 사용자/워크스페이스 이름에 고유 토큰을 붙인다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(classes = [TestcontainersConfig::class])
class AuthorizationIntegrationTest(
    private val mockMvc: MockMvc,
    private val converter: AppUserJwtAuthenticationConverter,
    private val appUserRepository: AppUserRepository,
) {
    private val jsonMapper = JsonMapper()

    private fun token() = UUID.randomUUID().toString().take(8)

    private inner class TestUser(val subject: String = "sub-${token()}") {
        val email = "$subject@test"

        fun auth() = authentication(
            converter.convert(
                Jwt.withTokenValue("test")
                    .header("alg", "none")
                    .claim("iss", "test-issuer") // URL 이 아닌 issuer (local 토큰과 같은 형태)
                    .subject(subject)
                    .claim("email", email)
                    .build()
            )
        )

        val id: UUID by lazy { UUID.fromString(me()["user"]["id"].asString()) }

        fun me(): JsonNode = json(perform(get("/me"), 200))

        fun perform(request: MockHttpServletRequestBuilder, expectedStatus: Int): String =
            mockMvc.perform(request.with(auth()))
                .andExpect(status().`is`(expectedStatus))
                .andReturn().response.contentAsString
    }

    private fun MockHttpServletRequestBuilder.body(value: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(value))

    private fun json(content: String): JsonNode = jsonMapper.readTree(content)

    private fun TestUser.createWorkspace(): UUID {
        val response = perform(post("/workspaces").body(mapOf("name" to "ws-${token()}")), 201)
        return UUID.fromString(json(response)["id"].asString())
    }

    private fun TestUser.addMember(workspaceId: UUID, member: TestUser, role: String) {
        member.me() // 첫 요청 시점에 가입되므로, 이메일로 찾을 수 있게 먼저 로그인시킨다.
        perform(
            post("/workspaces/$workspaceId/members").body(mapOf("email" to member.email, "role" to role)),
            201,
        )
    }

    private fun platformAdmin(): TestUser {
        val admin = TestUser()
        val user = appUserRepository.findById(admin.id).orElseThrow()
        user.platformRole = PlatformRole.ADMIN
        appUserRepository.save(user)
        return admin
    }

    @Test
    fun `first login registers platform user and creator becomes workspace admin`() {
        val user = TestUser()
        assertEquals("USER", user.me()["user"]["platformRole"].asString())
        assertTrue(user.me()["memberships"].isEmpty)

        val workspaceId = user.createWorkspace()

        val membership = user.me()["memberships"].single()
        assertEquals(workspaceId.toString(), membership["workspaceId"].asString())
        assertEquals("ADMIN", membership["role"].asString())

        // 같은 (iss, sub) 로 다시 로그인해도 사용자가 중복 생성되지 않는다.
        assertEquals(user.id, UUID.fromString(user.me()["user"]["id"].asString()))
    }

    @Test
    fun `non member cannot access workspace and does not see it in list`() {
        val owner = TestUser()
        val outsider = TestUser()
        val workspaceId = owner.createWorkspace()

        outsider.perform(get("/workspaces/$workspaceId"), 403)
        outsider.perform(get("/workspaces/$workspaceId/prompts"), 403)
        outsider.perform(get("/workspaces/$workspaceId/members"), 403)

        val outsiderIds = json(outsider.perform(get("/workspaces?size=1000"), 200))["items"].map { it["id"].asString() }
        assertFalse(workspaceId.toString() in outsiderIds)
        val ownerIds = json(owner.perform(get("/workspaces?size=1000"), 200))["items"].map { it["id"].asString() }
        assertTrue(workspaceId.toString() in ownerIds)
    }

    @Test
    fun `workspace role hierarchy is enforced`() {
        val admin = TestUser()
        val member = TestUser()
        val workspaceId = admin.createWorkspace()
        admin.addMember(workspaceId, member, "GUEST")

        // GUEST: 읽기만 가능
        member.perform(get("/workspaces/$workspaceId"), 200)
        member.perform(get("/workspaces/$workspaceId/members"), 200)
        member.perform(
            post("/prompts").body(mapOf("workspaceId" to workspaceId, "name" to "p-${token()}", "instructions" to "hi")),
            403,
        )
        member.perform(
            post("/workspaces/$workspaceId/llm-calls")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .body(mapOf("llmModelId" to 1, "llmApiKeyId" to UUID.randomUUID())),
            403,
        )

        // USER: 프롬프트 작성 가능, 공통 설정 변경은 불가
        admin.perform(patch("/workspaces/$workspaceId/members/${member.id}").body(mapOf("role" to "USER")), 200)
        val prompt = json(
            member.perform(
                post("/prompts").body(mapOf("workspaceId" to workspaceId, "name" to "p-${token()}", "instructions" to "hi")),
                201,
            )
        )
        member.perform(get("/prompts/${prompt["id"].asString()}"), 200)
        member.perform(put("/workspaces/$workspaceId/default-options").body(mapOf("temperature" to 0.5)), 403)
        member.perform(patch("/workspaces/$workspaceId").body(mapOf("name" to "renamed")), 403)
        member.perform(
            post("/workspaces/$workspaceId/members").body(mapOf("email" to TestUser().email, "role" to "GUEST")),
            403,
        )

        // ADMIN: 공통 설정 변경 가능
        admin.perform(put("/workspaces/$workspaceId/default-options").body(mapOf("temperature" to 0.5)), 200)
    }

    @Test
    fun `resources of other workspaces are protected by id`() {
        val owner = TestUser()
        val outsider = TestUser()
        val workspaceId = owner.createWorkspace()
        val promptId = json(
            owner.perform(
                post("/prompts").body(mapOf("workspaceId" to workspaceId, "name" to "p-${token()}", "instructions" to "hi")),
                201,
            )
        )["id"].asString()

        outsider.perform(get("/prompts/$promptId"), 403)
        outsider.perform(delete("/prompts/$promptId"), 403)
        outsider.perform(get("/prompts?workspaceId=$workspaceId"), 403)
        // 워크스페이스를 지정하지 않은 전역 목록은 플랫폼 ADMIN 만 조회할 수 있다.
        owner.perform(get("/prompts"), 403)
        owner.perform(get("/llm-calls"), 403)
        // 없는 리소스는 권한 검사를 통과시키고 404 를 응답한다.
        outsider.perform(get("/prompts/${UUID.randomUUID()}"), 404)
    }

    @Test
    fun `last workspace admin cannot be demoted or removed`() {
        val admin = TestUser()
        val workspaceId = admin.createWorkspace()

        admin.perform(patch("/workspaces/$workspaceId/members/${admin.id}").body(mapOf("role" to "USER")), 409)
        admin.perform(delete("/workspaces/$workspaceId/members/${admin.id}"), 409)

        val other = TestUser()
        admin.addMember(workspaceId, other, "ADMIN")
        admin.perform(delete("/workspaces/$workspaceId/members/${admin.id}"), 204)
        assertTrue(admin.me()["memberships"].isEmpty)
    }

    @Test
    fun `global settings are writable only by platform admin`() {
        val user = TestUser()
        val admin = platformAdmin()

        user.perform(get("/llm-vendors"), 200)
        user.perform(get("/models"), 200)
        user.perform(post("/llm-vendors").body(mapOf("name" to "v-${token()}")), 403)
        user.perform(get("/users"), 403)

        admin.perform(post("/llm-vendors").body(mapOf("name" to "v-${token()}")), 201)
        admin.perform(get("/users"), 200)
    }

    @Test
    fun `platform admin is admin of every workspace`() {
        val owner = TestUser()
        val admin = platformAdmin()
        val workspaceId = owner.createWorkspace()

        admin.perform(get("/workspaces/$workspaceId"), 200)
        admin.perform(put("/workspaces/$workspaceId/default-options").body(mapOf("temperature" to 0.1)), 200)
        val adminIds = json(admin.perform(get("/workspaces?size=1000"), 200))["items"].map { it["id"].asString() }
        assertTrue(workspaceId.toString() in adminIds)
    }

    @Test
    fun `platform role change guards self change`() {
        val admin = platformAdmin()
        val user = TestUser()

        admin.perform(patch("/users/${admin.id}/platform-role").body(mapOf("platformRole" to "USER")), 400)
        admin.perform(patch("/users/${user.id}/platform-role").body(mapOf("platformRole" to "ADMIN")), 200)
        assertEquals("ADMIN", user.me()["user"]["platformRole"].asString())
    }

    @Test
    fun `cancel of unknown call log responds not found`() {
        val outsider = TestUser()
        // 없는 로그는 권한 검사를 통과시키고 서비스가 404 를 응답한다.
        outsider.perform(post("/llm-calls/${UUID.randomUUID()}/cancel"), 404)
    }
}
