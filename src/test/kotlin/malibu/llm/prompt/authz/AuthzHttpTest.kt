package malibu.llm.prompt.authz

import malibu.llm.prompt.config.LocalJwtProperties
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.data.repo.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * 실제 보안 필터 체인(local 프로파일: /dev/token 발급 토큰)으로 인증 → 판정 → HTTP 상태 매핑을 검사한다.
 * - NOT_MEMBER 404, DENIED 403(code=FORBIDDEN), 토큰 없음/무효 401, 비활성 사용자 403(code=USER_DISABLED)
 *
 * 판정 대상 엔드포인트는 이 테스트에만 등록되는 ProbeController 를 쓴다(4단계 전이라 실제 컨트롤러에는 아직 @PreAuthorize 가 없다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthzHttpTest(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val jwtEncoder: JwtEncoder,
    private val localJwtProperties: LocalJwtProperties,
    jdbcTemplate: JdbcTemplate,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("authz_http_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)
    }

    @TestConfiguration
    @Import(ProbeController::class)
    class ProbeConfig

    @RestController
    @RequestMapping("/test-authz")
    class ProbeController {
        @GetMapping("/platform")
        @PreAuthorize("@platformAuthz.has('${Permissions.LLM_MODEL_MANAGE}')")
        fun platform() = "ok"

        @GetMapping("/organizations/{organizationId}/update")
        @PreAuthorize("@orgAuthz.has(#organizationId, '${Permissions.ORGANIZATION_UPDATE}')")
        fun orgUpdate(@PathVariable organizationId: UUID) = "ok"

        @GetMapping("/workspaces/{workspaceId}/prompt-read")
        @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_READ}')")
        fun promptRead(@PathVariable workspaceId: UUID) = "ok"

        @GetMapping("/workspaces/{workspaceId}/prompt-delete")
        @PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_DELETE}')")
        fun promptDelete(@PathVariable workspaceId: UUID) = "ok"
    }

    private val jsonMapper = JsonMapper()
    private val fixture = MembershipFixture(jdbcTemplate)

    private lateinit var adminToken: String
    private lateinit var ownerToken: String
    private lateinit var memberToken: String
    private lateinit var outsiderToken: String
    private lateinit var owner: User
    private var ownerOrgId: UUID = UUID.randomUUID()
    private var ownerWorkspaceId: UUID = UUID.randomUUID()

    private fun issueToken(email: String, name: String? = null): ResultActionsDsl =
        mockMvc.post("/dev/token") {
            contentType = MediaType.APPLICATION_JSON
            content = jsonMapper.writeValueAsString(mapOf("email" to email, "name" to name))
        }

    private fun tokenOf(email: String): String {
        val body = issueToken(email).andReturn().response.contentAsString
        return jsonMapper.readTree(body).get("accessToken").asText()
    }

    private fun getAs(token: String?, path: String): ResultActionsDsl =
        mockMvc.get(path) { token?.let { header("Authorization", "Bearer $it") } }

    private fun ResultActionsDsl.expectStatus(status: Int, code: String? = null) {
        andExpect {
            status { isEqualTo(status) }
            if (code != null) jsonPath("$.code") { value(code) }
        }
    }

    @BeforeAll
    fun setUp() {
        // 첫 사용자 = PLATFORM_ADMIN
        adminToken = tokenOf("admin@test.local")
        ownerToken = tokenOf("owner@test.local")
        memberToken = tokenOf("member@test.local")
        outsiderToken = tokenOf("outsider@test.local")

        owner = userRepository.findByEmail("owner@test.local")!!
        ownerOrgId = fixture.personalOrganizationId(owner)
        ownerWorkspaceId = fixture.defaultWorkspaceId(owner)

        val member = userRepository.findByEmail("member@test.local")!!
        fixture.addOrgMember(ownerOrgId, member, Roles.ORG_MEMBER)
        fixture.addWorkspaceMember(ownerWorkspaceId, ownerOrgId, member, Roles.WORKSPACE_MEMBER)
    }

    @Test
    fun `토큰이 없거나 유효하지 않으면 401`() {
        getAs(null, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(401)
        getAs("not-a-jwt", "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(401)
    }

    @Test
    fun `서명은 유효하지만 없는 사용자의 토큰은 401`() {
        val claims = JwtClaimsSet.builder()
            .issuer(localJwtProperties.issuer)
            .subject(UUID.randomUUID().toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue

        getAs(token, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(401)
    }

    @Test
    fun `플랫폼 권한 - PLATFORM_ADMIN 200, 일반 사용자 403`() {
        getAs(adminToken, "/test-authz/platform").expectStatus(200)
        getAs(ownerToken, "/test-authz/platform").expectStatus(403, "FORBIDDEN")
    }

    @Test
    fun `workspace 권한 - 권한 있음 200, 멤버지만 권한 없음 403, 비멤버 404`() {
        getAs(ownerToken, "/test-authz/workspaces/$ownerWorkspaceId/prompt-delete").expectStatus(200)
        getAs(memberToken, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(200)
        getAs(memberToken, "/test-authz/workspaces/$ownerWorkspaceId/prompt-delete").expectStatus(403, "FORBIDDEN")
        getAs(outsiderToken, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(404)
        // PLATFORM_ADMIN 도 콘텐츠 권한은 없다
        getAs(adminToken, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(404)
    }

    @Test
    fun `없는 workspace 와 남의 workspace 는 같은 404 응답이다`() {
        val notExists = getAs(outsiderToken, "/test-authz/workspaces/${UUID.randomUUID()}/prompt-read")
            .andReturn().response
        val others = getAs(outsiderToken, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read")
            .andReturn().response
        assertEquals(404, notExists.status)
        assertEquals(404, others.status)
        assertEquals(
            jsonMapper.readTree(notExists.contentAsString).get("message"),
            jsonMapper.readTree(others.contentAsString).get("message"),
        )
    }

    @Test
    fun `org 권한 - OWNER 200, ORG_MEMBER 403, 비멤버 404`() {
        getAs(ownerToken, "/test-authz/organizations/$ownerOrgId/update").expectStatus(200)
        getAs(memberToken, "/test-authz/organizations/$ownerOrgId/update").expectStatus(403, "FORBIDDEN")
        getAs(outsiderToken, "/test-authz/organizations/$ownerOrgId/update").expectStatus(404)
    }

    @Test
    fun `dev token 은 같은 email 이면 같은 사용자로 발급한다`() {
        tokenOf("owner@test.local")
        assertEquals(
            1,
            userRepository.findAll().count { it.email == "owner@test.local" },
        )
    }

    @Test
    fun `비활성화된 사용자는 토큰 발급도, 기존 토큰 사용도 403 USER_DISABLED`() {
        val token = tokenOf("disabled@test.local")
        val user = userRepository.findByEmail("disabled@test.local")!!
        user.status = malibu.llm.prompt.data.UserStatus.DISABLED
        userRepository.save(user)

        getAs(token, "/test-authz/workspaces/$ownerWorkspaceId/prompt-read").expectStatus(403, "USER_DISABLED")
        issueToken("disabled@test.local").expectStatus(403, "USER_DISABLED")
    }

    @Test
    fun `dev init 은 인증이 필요하고 호출한 사용자의 기본 workspace 에 샘플 프롬프트를 만든다`() {
        mockMvc.post("/dev/init").andExpect { status { isEqualTo(401) } }

        val token = tokenOf("init@test.local")
        val user = userRepository.findByEmail("init@test.local")!!
        mockMvc.post("/dev/init") { header("Authorization", "Bearer $token") }
            .andExpect { status { isEqualTo(201) } }

        val prompts = fixture.let {
            jdbcTemplateCount("select count(*) from prompt where workspace_id = '${it.defaultWorkspaceId(user)}'")
        }
        assertEquals(1, prompts)
    }

    private val jdbc = jdbcTemplate

    private fun jdbcTemplateCount(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!
}
