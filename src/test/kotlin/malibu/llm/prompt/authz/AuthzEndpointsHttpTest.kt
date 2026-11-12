package malibu.llm.prompt.authz

import malibu.llm.prompt.data.entity.User
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
 * 실제 컨트롤러 + 보안 필터 체인으로 §4.5 매핑이 HTTP 에서 동작하는지 검사한다. (local 프로파일, Liquibase PostgreSQL)
 *
 * 사용자
 * - admin: 첫 가입자(PLATFORM_ADMIN)
 * - owner: 개인 org 의 ORG_OWNER, 기본 workspace(ownerWs)
 * - member: owner org 의 ORG_MEMBER + ownerWs 의 WORKSPACE_MEMBER
 * - outsider: owner org 와 무관
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthzEndpointsHttpTest(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    jdbcTemplate: JdbcTemplate,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("authz_endpoints_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)
    }

    private val json = JsonMapper()
    private val fixture = MembershipFixture(jdbcTemplate)

    private lateinit var admin: String
    private lateinit var owner: String
    private lateinit var member: String
    private lateinit var outsider: String
    private lateinit var ownerUser: User
    private lateinit var ownerOrgId: UUID
    private lateinit var ownerWsId: UUID

    private data class Res(val status: Int, val body: JsonNode?) {
        fun code(): String? = body?.get("code")?.asText()
    }

    private fun call(method: HttpMethod, token: String?, path: String, body: Any? = null, accept: MediaType? = null): Res {
        val builder = MockMvcRequestBuilders.request(method, path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        accept?.let { builder.accept(it) }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        }
        val response = mockMvc.perform(builder).andReturn().response
        val text = response.contentAsString
        val node = if (text.isNotBlank() && text.trimStart().startsWith("{") || text.trimStart().startsWith("[")) {
            json.readTree(text)
        } else {
            null
        }
        return Res(response.status, node)
    }

    private fun get(token: String?, path: String) = call(HttpMethod.GET, token, path)
    private fun post(token: String?, path: String, body: Any? = emptyMap<String, Any>()) = call(HttpMethod.POST, token, path, body)
    private fun patch(token: String?, path: String, body: Any) = call(HttpMethod.PATCH, token, path, body)
    private fun delete(token: String?, path: String) = call(HttpMethod.DELETE, token, path)

    private fun tokenOf(email: String): String =
        post(null, "/dev/token", mapOf("email" to email, "name" to email.substringBefore("@"))).body!!
            .get("accessToken").asText()

    private fun user(email: String): User = userRepository.findByEmail(email)!!

    private fun newUser(prefix: String): Pair<String, User> {
        val email = "$prefix-${UUID.randomUUID()}@test.local"
        val token = tokenOf(email)
        return token to user(email)
    }

    private fun assertStatus(expected: Int, res: Res, code: String? = null) {
        assertEquals(expected, res.status, "body: ${res.body}")
        if (code != null) assertEquals(code, res.code())
    }

    private fun JsonNode.texts(field: String): Set<String> = get(field).map { it.asText() }.toSet()

    @BeforeAll
    fun setUp() {
        admin = tokenOf("admin@test.local")
        owner = tokenOf("owner@test.local")
        member = tokenOf("member@test.local")
        outsider = tokenOf("outsider@test.local")

        ownerUser = user("owner@test.local")
        ownerOrgId = fixture.personalOrganizationId(ownerUser)
        ownerWsId = fixture.defaultWorkspaceId(ownerUser)
        val memberUser = user("member@test.local")
        fixture.addOrgMember(ownerOrgId, memberUser, Roles.ORG_MEMBER)
        fixture.addWorkspaceMember(ownerWsId, ownerOrgId, memberUser, Roles.WORKSPACE_MEMBER)
    }

    // ---- 내 정보 ----

    @Test
    fun `me - 플랫폼 권한과 소속 org`() {
        val adminMe = get(admin, "/me").body!!
        assertEquals(setOf(Roles.PLATFORM_ADMIN), adminMe.texts("platformRoles"))
        assertTrue(Permissions.ORGANIZATION_CREATE in adminMe.texts("platformPermissions"))

        val ownerMe = get(owner, "/me").body!!
        assertTrue(ownerMe.texts("platformPermissions").isEmpty())
        val orgs = ownerMe.get("organizations")
        assertEquals(1, orgs.size())
        assertEquals(ownerOrgId.toString(), orgs[0].get("id").asText())
        assertEquals(Roles.ORG_OWNER, orgs[0].get("role").asText())

        assertStatus(401, get(null, "/me"))
    }

    @Test
    fun `workspace 목록은 접근 가능한 것만`() {
        fun ids(token: String) = get(token, "/workspaces?size=100").body!!.get("items").map { it.get("id").asText() }.toSet()

        assertTrue(ownerWsId.toString() in ids(owner))
        assertTrue(ownerWsId.toString() in ids(member))
        assertEquals(2, ids(member).size) // 자기 기본 workspace + ownerWs
        assertFalse(ownerWsId.toString() in ids(outsider))
        // PLATFORM_ADMIN 도 /workspaces 에서는 자기 것만
        assertFalse(ownerWsId.toString() in ids(admin))
    }

    @Test
    fun `my-permissions - workspace 권한과 workspace 에 대한 org 권한`() {
        val memberPerms = get(member, "/workspaces/$ownerWsId/my-permissions").body!!
        assertTrue(Permissions.PROMPT_WRITE in memberPerms.texts("permissions"))
        assertFalse(Permissions.PROMPT_DELETE in memberPerms.texts("permissions"))
        assertEquals(setOf(Permissions.ORGANIZATION_READ), memberPerms.texts("organizationPermissions"))

        val ownerPerms = get(owner, "/workspaces/$ownerWsId/my-permissions").body!!
        assertTrue(Permissions.WORKSPACE_DELETE in ownerPerms.texts("organizationPermissions"))

        val orgPerms = get(member, "/organizations/$ownerOrgId/my-permissions").body!!
        assertEquals(setOf(Permissions.ORGANIZATION_READ), orgPerms.texts("permissions"))

        assertStatus(404, get(outsider, "/workspaces/$ownerWsId/my-permissions"))
        assertStatus(404, get(outsider, "/organizations/$ownerOrgId/my-permissions"))
    }

    // ---- workspace ----

    @Test
    fun `workspace 생성은 org 권한, 삭제는 org(ws) 권한`() {
        val path = "/organizations/$ownerOrgId/workspaces"
        assertStatus(403, post(member, path, mapOf("name" to "by-member")), "FORBIDDEN")
        assertStatus(404, post(outsider, path, mapOf("name" to "by-outsider")))

        val created = post(owner, path, mapOf("name" to "ws-lifecycle"))
        assertStatus(201, created)
        val wsId = UUID.fromString(created.body!!.get("id").asText())
        // 생성자는 WORKSPACE_ADMIN 멤버로 등록된다
        val members = get(owner, "/workspaces/$wsId/members").body!!.get("items")
        assertEquals(listOf(Roles.WORKSPACE_ADMIN), members.map { it.get("role").asText() })

        // ORG_MEMBER + WORKSPACE_ADMIN: 수정은 되지만 삭제는 안 된다
        fixture.addWorkspaceMember(wsId, ownerOrgId, user("member@test.local"), Roles.WORKSPACE_ADMIN)
        assertStatus(200, patch(member, "/workspaces/$wsId", mapOf("description" to "edited")))
        assertStatus(403, delete(member, "/workspaces/$wsId"), "FORBIDDEN")
        assertStatus(404, delete(outsider, "/workspaces/$wsId"))

        assertStatus(204, delete(owner, "/workspaces/$wsId"))
        assertStatus(404, get(owner, "/workspaces/$wsId"))
    }

    @Test
    fun `workspace 멤버 - org 멤버만 추가할 수 있다`() {
        val path = "/workspaces/$ownerWsId/members"
        assertStatus(400, post(owner, path, mapOf("email" to "outsider@test.local", "role" to Roles.WORKSPACE_MEMBER)))
        assertStatus(409, post(owner, path, mapOf("email" to "member@test.local", "role" to Roles.WORKSPACE_MEMBER)))
        assertStatus(400, post(owner, path, mapOf("email" to "member@test.local", "role" to Roles.ORG_ADMIN)))
        assertStatus(403, post(member, path, mapOf("email" to "owner@test.local", "role" to Roles.WORKSPACE_MEMBER)))
    }

    // ---- prompt ----

    @Test
    fun `prompt - MEMBER 는 작성, ADMIN 만 삭제와 publish`() {
        val base = "/workspaces/$ownerWsId/prompts"
        assertStatus(404, get(outsider, base))

        val created = post(member, base, mapOf("name" to "p1", "instructions" to "hello"))
        assertStatus(201, created)
        val promptId = created.body!!.get("id").asText()
        val mainRefId = created.body!!.get("defaultBranchRefId").asLong()
        assertStatus(200, get(member, "$base/$promptId"))

        // ref: BRANCH 는 write, TAG 는 publish
        assertStatus(201, post(member, "$base/$promptId/refs", mapOf("name" to "dev", "type" to "BRANCH", "fromRefId" to mainRefId)))
        assertStatus(403, post(member, "$base/$promptId/refs", mapOf("name" to "v1", "type" to "TAG", "fromRefId" to mainRefId)), "FORBIDDEN")
        val tag = post(owner, "$base/$promptId/refs", mapOf("name" to "v1", "type" to "TAG", "fromRefId" to mainRefId))
        assertStatus(201, tag)
        val tagId = tag.body!!.get("id").asLong()
        val tag2Id = post(owner, "$base/$promptId/refs", mapOf("name" to "v2", "type" to "TAG", "fromRefId" to mainRefId))
            .body!!.get("id").asLong()

        // latestTag 변경은 publish
        assertStatus(200, patch(member, "$base/$promptId", mapOf("description" to "edited")))
        assertStatus(403, patch(member, "$base/$promptId", mapOf("latestTagRefId" to tagId)), "FORBIDDEN")
        assertStatus(200, patch(owner, "$base/$promptId", mapOf("latestTagRefId" to tagId)))

        // TAG 삭제는 publish (메서드 안에서 검사)
        assertStatus(403, delete(member, "$base/$promptId/refs/$tag2Id"), "FORBIDDEN")
        assertStatus(204, delete(owner, "$base/$promptId/refs/$tag2Id"))

        assertStatus(403, delete(member, "$base/$promptId"), "FORBIDDEN")
        assertStatus(204, delete(owner, "$base/$promptId"))
    }

    // ---- api key / 카탈로그 ----

    @Test
    fun `api key - 목록은 llm execute, 생성은 api key manage`() {
        assertStatus(403, post(owner, "/llm-vendors", mapOf("name" to "x")), "FORBIDDEN")
        val vendor = post(admin, "/llm-vendors", mapOf("name" to "openai-${UUID.randomUUID()}"))
        assertStatus(201, vendor)
        val vendorId = vendor.body!!.get("id").asText()
        assertStatus(200, get(member, "/llm-vendors"))
        assertStatus(200, get(member, "/models"))
        assertStatus(403, post(owner, "/models", mapOf("vendorId" to vendorId, "name" to "m", "modelKey" to "m", "modelType" to "STANDARD")), "FORBIDDEN")

        val base = "/workspaces/$ownerWsId/llm-api-keys"
        val key = mapOf("vendorId" to vendorId, "name" to "k", "value" to "sk-test-12345")
        assertStatus(403, post(member, base, key), "FORBIDDEN")
        assertStatus(201, post(owner, base, key))
        val list = get(member, base)
        assertStatus(200, list)
        assertTrue(list.body!!.get("items").size() >= 1)
        assertStatus(404, get(outsider, base))
    }

    @Test
    fun `llm 실행(SSE) - 비멤버는 404 JSON`() {
        val res = call(
            HttpMethod.POST, outsider, "/workspaces/$ownerWsId/llm-calls",
            body = mapOf("llmModelId" to 1, "llmApiKeyId" to UUID.randomUUID(), "rawInstructionsText" to "hi"),
            accept = MediaType.TEXT_EVENT_STREAM,
        )
        assertStatus(404, res)
    }

    // ---- organization / 멤버 ----

    @Test
    fun `org 멤버 - OWNER 는 멤버 관리로 부여, 변경, 추방할 수 없다`() {
        val (_, newcomer) = newUser("newcomer")
        val path = "/organizations/$ownerOrgId/members"

        assertStatus(403, post(owner, path, mapOf("email" to newcomer.email, "role" to Roles.ORG_OWNER)), "OWNER_CHANGE_NOT_ALLOWED")
        assertStatus(404, post(owner, path, mapOf("email" to "nobody@test.local", "role" to Roles.ORG_MEMBER)))
        assertStatus(400, post(owner, path, mapOf("email" to newcomer.email, "role" to Roles.WORKSPACE_ADMIN)))
        assertStatus(403, post(member, path, mapOf("email" to newcomer.email, "role" to Roles.ORG_MEMBER)), "FORBIDDEN")

        assertStatus(201, post(owner, path, mapOf("email" to newcomer.email, "role" to Roles.ORG_MEMBER)))
        assertStatus(409, post(owner, path, mapOf("email" to newcomer.email, "role" to Roles.ORG_MEMBER)))
        assertStatus(200, patch(owner, "$path/${newcomer.id}", mapOf("role" to Roles.ORG_ADMIN)))
        assertStatus(403, patch(owner, "$path/${ownerUser.id}", mapOf("role" to Roles.ORG_ADMIN)), "OWNER_CHANGE_NOT_ALLOWED")
        assertStatus(403, delete(owner, "$path/${ownerUser.id}"), "OWNER_CHANGE_NOT_ALLOWED")
        assertStatus(204, delete(owner, "$path/${newcomer.id}"))
        assertStatus(404, delete(owner, "$path/${newcomer.id}"))
    }

    @Test
    fun `소유권 이전 - 대상은 OWNER, 기존 OWNER 는 ORG_ADMIN`() {
        val (fromToken, from) = newUser("from")
        val (toToken, to) = newUser("to")
        val orgId = fixture.personalOrganizationId(from)
        fixture.addOrgMember(orgId, to, Roles.ORG_MEMBER)

        assertStatus(403, post(toToken, "/organizations/$orgId/ownership-transfer", mapOf("userId" to from.id)), "FORBIDDEN")
        assertStatus(204, post(fromToken, "/organizations/$orgId/ownership-transfer", mapOf("userId" to to.id)))

        val roles = get(toToken, "/organizations/$orgId/members").body!!.get("items")
            .associate { it.get("userId").asText() to it.get("role").asText() }
        assertEquals(Roles.ORG_OWNER, roles[to.id.toString()])
        assertEquals(Roles.ORG_ADMIN, roles[from.id.toString()])
        assertFalse(Permissions.ORGANIZATION_OWNERSHIP_TRANSFER in get(fromToken, "/organizations/$orgId/my-permissions").body!!.texts("permissions"))
    }

    @Test
    fun `org 삭제 - workspace 가 남아 있으면 409, 비우면 204`() {
        val (token, u) = newUser("deleter")
        val orgId = fixture.personalOrganizationId(u)
        val wsId = fixture.defaultWorkspaceId(u)
        assertStatus(404, delete(owner, "/organizations/$orgId"))
        assertStatus(409, delete(token, "/organizations/$orgId"))
        assertStatus(204, delete(token, "/workspaces/$wsId"))
        assertStatus(204, delete(token, "/organizations/$orgId"))
        assertTrue(get(token, "/me").body!!.get("organizations").isEmpty)
    }

    @Test
    fun `org 의 workspace 목록 - list_all 이 없으면 소속 workspace 만`() {
        val extra = post(owner, "/organizations/$ownerOrgId/workspaces", mapOf("name" to "owner-only")).body!!.get("id").asText()
        fun ids(token: String) = get(token, "/organizations/$ownerOrgId/workspaces?size=100").body!!.get("items")
            .map { it.get("id").asText() }.toSet()

        assertTrue(extra in ids(owner))
        assertFalse(extra in ids(member))
        assertTrue(ownerWsId.toString() in ids(member))
        assertStatus(204, delete(owner, "/workspaces/$extra"))
    }

    // ---- 플랫폼 관리 ----

    @Test
    fun `admin 사용자 - 사전 등록, 권한 없는 사용자는 403`() {
        assertStatus(403, get(owner, "/admin/users"), "FORBIDDEN")
        assertStatus(200, get(admin, "/admin/users"))

        val email = "preregistered-${UUID.randomUUID()}@test.local"
        val created = post(admin, "/admin/users", mapOf("email" to email, "name" to "pre"))
        assertStatus(201, created)
        assertFalse(created.body!!.get("linked").asBoolean())
        assertStatus(409, post(admin, "/admin/users", mapOf("email" to email, "name" to "pre")))
        // 개인 org 가 함께 만들어진다
        val newUser = user(email)
        fixture.personalOrganizationId(newUser)

        val adminId = user("admin@test.local").id
        assertStatus(400, patch(admin, "/admin/users/$adminId", mapOf("status" to "DISABLED")))
        assertStatus(400, patch(admin, "/admin/users/$adminId", mapOf("platformRoles" to emptyList<String>())))
        val granted = patch(admin, "/admin/users/${newUser.id}", mapOf("platformRoles" to listOf(Roles.PLATFORM_ADMIN)))
        assertEquals(setOf(Roles.PLATFORM_ADMIN), granted.body!!.texts("platformRoles"))
        assertStatus(400, patch(admin, "/admin/users/${newUser.id}", mapOf("platformRoles" to listOf(Roles.ORG_OWNER))))
    }

    @Test
    fun `admin org - 생성과 정지, 정지된 org 는 404`() {
        assertStatus(403, post(owner, "/admin/organizations", mapOf("name" to "x")), "FORBIDDEN")
        val (token, u) = newUser("suspended")
        val created = post(admin, "/admin/organizations", mapOf("name" to "team", "ownerUserId" to u.id))
        assertStatus(201, created)
        val teamOrgId = created.body!!.get("id").asText()
        assertStatus(200, get(token, "/organizations/$teamOrgId"))
        // PLATFORM_ADMIN 은 org 를 만들어도 멤버가 아니다
        assertStatus(404, get(admin, "/organizations/$teamOrgId"))

        val wsId = fixture.defaultWorkspaceId(u)
        val personalOrgId = fixture.personalOrganizationId(u)
        assertStatus(200, patch(admin, "/admin/organizations/$personalOrgId/status", mapOf("status" to "SUSPENDED")))
        assertStatus(404, get(token, "/workspaces/$wsId"))
        assertFalse(wsId.toString() in get(token, "/workspaces").body!!.get("items").map { it.get("id").asText() })

        assertStatus(200, patch(admin, "/admin/organizations/$personalOrgId/status", mapOf("status" to "ACTIVE")))
        assertStatus(200, get(token, "/workspaces/$wsId"))
    }

    @Test
    fun `admin 전체 조회 - platform content read 만, 읽기 전용`() {
        post(owner, "/workspaces/$ownerWsId/prompts", mapOf("name" to "for-admin", "instructions" to "x"))
        assertStatus(403, get(owner, "/admin/prompts"), "FORBIDDEN")
        assertStatus(403, get(owner, "/admin/workspaces"), "FORBIDDEN")

        val prompts = get(admin, "/admin/prompts?workspaceId=$ownerWsId")
        assertStatus(200, prompts)
        assertTrue(prompts.body!!.get("items").any { it.get("name").asText() == "for-admin" })
        assertStatus(200, get(admin, "/admin/workspaces"))
        assertStatus(200, get(admin, "/admin/llm-calls"))
        assertStatus(200, get(admin, "/admin/llm-api-keys"))
        // 같은 콘텐츠도 workspace 경로로는 볼 수 없다
        assertStatus(404, get(admin, "/workspaces/$ownerWsId/prompts"))
    }
}
