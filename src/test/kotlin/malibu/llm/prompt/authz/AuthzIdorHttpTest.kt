package malibu.llm.prompt.authz

import malibu.llm.prompt.data.repo.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
 * IDOR 방어 (authz-spec §5, §9 "A workspace 권한으로 B workspace 의 리소스 ID 접근 시 404").
 *
 * a 는 자기 workspace(wsA)의 WORKSPACE_ADMIN 이다. 경로의 wsA 권한 검사는 통과하지만,
 * b 의 workspace(wsB)에 있는 리소스 ID 를 넣으면 모두 404 여야 하고 b 의 데이터는 바뀌지 않아야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthzIdorHttpTest(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val jdbcTemplate: JdbcTemplate,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("authz_idor_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)
    }

    private val json = JsonMapper()
    private val fixture = MembershipFixture(jdbcTemplate)

    private lateinit var a: String
    private lateinit var b: String
    private lateinit var wsA: UUID
    private lateinit var wsB: UUID

    // a 의 리소스
    private lateinit var promptA: String
    private var refA: Long = 0
    private lateinit var keyA: String
    private var modelId: Long = 0

    // b 의 리소스
    private lateinit var promptB: String
    private var refB: Long = 0
    private var itemB: Long = 0
    private lateinit var keyB: String
    private lateinit var callLogB: UUID

    private data class Res(val status: Int, val body: JsonNode?)

    private fun call(method: HttpMethod, token: String, path: String, body: Any? = null, accept: MediaType? = null): Res {
        val builder = MockMvcRequestBuilders.request(method, path).header("Authorization", "Bearer $token")
        accept?.let { builder.accept(it) }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
        }
        val response = mockMvc.perform(builder).andReturn().response
        val text = response.contentAsString.trim()
        return Res(response.status, if (text.startsWith("{")) json.readTree(text) else null)
    }

    private fun tokenOf(email: String): String {
        val res = mockMvc.perform(
            MockMvcRequestBuilders.post("/dev/token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(mapOf("email" to email, "name" to email.substringBefore("@"))))
        ).andReturn().response.contentAsString
        return json.readTree(res).get("accessToken").asText()
    }

    private fun created(res: Res): JsonNode {
        assertEquals(201, res.status, "body: ${res.body}")
        return res.body!!
    }

    @BeforeAll
    fun setUp() {
        val admin = tokenOf("admin@idor.local")
        a = tokenOf("a@idor.local")
        b = tokenOf("b@idor.local")
        wsA = fixture.defaultWorkspaceId(userRepository.findByEmail("a@idor.local")!!)
        wsB = fixture.defaultWorkspaceId(userRepository.findByEmail("b@idor.local")!!)

        val vendorId = created(call(HttpMethod.POST, admin, "/llm-vendors", mapOf("name" to "openai"))).get("id").asText()
        modelId = created(
            call(HttpMethod.POST, admin, "/models", mapOf("vendorId" to vendorId, "name" to "m", "modelKey" to "m", "modelType" to "STANDARD"))
        ).get("id").asLong()

        fun setUpWorkspace(token: String, ws: UUID): Triple<JsonNode, JsonNode, JsonNode> {
            val prompt = created(call(HttpMethod.POST, token, "/workspaces/$ws/prompts", mapOf("name" to "p", "instructions" to "original")))
            val ref = prompt.get("defaultBranchRefId").asLong()
            val item = created(
                call(HttpMethod.POST, token, "/workspaces/$ws/prompts/${prompt.get("id").asText()}/refs/$ref/additional-input-items",
                    mapOf("role" to "USER", "message" to "original"))
            )
            val key = created(call(HttpMethod.POST, token, "/workspaces/$ws/llm-api-keys", mapOf("vendorId" to vendorId, "name" to "k", "value" to "sk-original")))
            return Triple(prompt, item, key)
        }

        val (pA, _, kA) = setUpWorkspace(a, wsA)
        promptA = pA.get("id").asText()
        refA = pA.get("defaultBranchRefId").asLong()
        keyA = kA.get("id").asText()

        val (pB, iB, kB) = setUpWorkspace(b, wsB)
        promptB = pB.get("id").asText()
        refB = pB.get("defaultBranchRefId").asLong()
        itemB = iB.get("id").asLong()
        keyB = kB.get("id").asText()

        callLogB = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into llm_call_log (id, llm_model_id, llm_api_key_id, instructions, elapsed, status, workspace_id, created_at, updated_at)
            values (?, ?, ?, 'x', 0, 'SUCCESS', ?, now(), now())
            """.trimIndent(),
            callLogB, modelId, UUID.fromString(keyB), wsB,
        )
    }

    private fun assert404(method: HttpMethod, path: String, body: Any? = null) {
        val res = call(method, a, path, body)
        assertEquals(404, res.status, "$method $path -> ${res.status} ${res.body}")
    }

    @Test
    fun `prompt - 다른 workspace 의 prompt ID 는 404`() {
        val base = "/workspaces/$wsA/prompts/$promptB"
        assert404(HttpMethod.GET, base)
        assert404(HttpMethod.PATCH, base, mapOf("name" to "hacked"))
        assert404(HttpMethod.DELETE, base)
        assert404(HttpMethod.GET, "$base/refs")
        assert404(HttpMethod.POST, "$base/refs", mapOf("name" to "x", "type" to "BRANCH", "fromRefId" to refB))
        assert404(HttpMethod.GET, "$base/refs/$refB")
        assert404(HttpMethod.PATCH, "$base/refs/$refB", mapOf("instructions" to "hacked"))
        assert404(HttpMethod.DELETE, "$base/refs/$refB")
        assert404(HttpMethod.GET, "$base/refs/$refB/additional-input-items")
        assert404(HttpMethod.POST, "$base/refs/$refB/additional-input-items", mapOf("role" to "USER", "message" to "hacked"))
        assert404(HttpMethod.PATCH, "$base/refs/$refB/additional-input-items/$itemB", mapOf("message" to "hacked"))
        assert404(HttpMethod.DELETE, "$base/refs/$refB/additional-input-items/$itemB")
    }

    @Test
    fun `prompt - 내 prompt 경로에 남의 ref, additional input ID 를 섞어도 404`() {
        val base = "/workspaces/$wsA/prompts/$promptA"
        assert404(HttpMethod.GET, "$base/refs/$refB")
        assert404(HttpMethod.PATCH, "$base/refs/$refB", mapOf("instructions" to "hacked"))
        assert404(HttpMethod.DELETE, "$base/refs/$refB")
        assert404(HttpMethod.GET, "$base/refs/$refB/additional-input-items")
        // 남의 ref 를 복사 원본으로
        assert404(HttpMethod.POST, "$base/refs", mapOf("name" to "copy", "type" to "BRANCH", "fromRefId" to refB))
        // 남의 ref 를 default branch 로
        assert404(HttpMethod.PATCH, base, mapOf("defaultBranchRefId" to refB))
        // 내 ref 아래에 남의 additional input
        assert404(HttpMethod.PATCH, "$base/refs/$refA/additional-input-items/$itemB", mapOf("message" to "hacked"))
        assert404(HttpMethod.DELETE, "$base/refs/$refA/additional-input-items/$itemB")
    }

    @Test
    fun `api key 와 호출 로그 - 다른 workspace 의 ID 는 404`() {
        val keys = "/workspaces/$wsA/llm-api-keys/$keyB"
        assert404(HttpMethod.GET, keys)
        assert404(HttpMethod.PATCH, keys, mapOf("name" to "hacked"))
        assert404(HttpMethod.DELETE, keys)

        assert404(HttpMethod.GET, "/workspaces/$wsA/llm-calls/$callLogB")
        assert404(HttpMethod.POST, "/workspaces/$wsA/llm-calls/$callLogB/cancel", emptyMap<String, Any>())
    }

    @Test
    fun `llm 실행 - 다른 workspace 의 api key, prompt ref 는 404`() {
        val path = "/workspaces/$wsA/llm-calls"
        val withOthersKey = call(
            HttpMethod.POST, a, path,
            mapOf("llmModelId" to modelId, "llmApiKeyId" to keyB, "rawInstructionsText" to "hi"),
            accept = MediaType.TEXT_EVENT_STREAM,
        )
        assertEquals(404, withOthersKey.status, "body: ${withOthersKey.body}")

        val withOthersRef = call(
            HttpMethod.POST, a, path,
            mapOf("llmModelId" to modelId, "llmApiKeyId" to keyA, "promptRefId" to refB),
            accept = MediaType.TEXT_EVENT_STREAM,
        )
        assertEquals(404, withOthersRef.status, "body: ${withOthersRef.body}")
    }

    @Test
    fun `b 의 데이터는 바뀌지 않았고 b 는 그대로 접근할 수 있다`() {
        // 다른 테스트의 시도와 순서에 무관하게: 시도 후 상태를 확인
        `prompt - 다른 workspace 의 prompt ID 는 404`()
        `api key 와 호출 로그 - 다른 workspace 의 ID 는 404`()

        val prompt = call(HttpMethod.GET, b, "/workspaces/$wsB/prompts/$promptB")
        assertEquals(200, prompt.status)
        assertEquals("p", prompt.body!!.get("name").asText())
        val ref = call(HttpMethod.GET, b, "/workspaces/$wsB/prompts/$promptB/refs/$refB").body!!
        assertEquals("original", ref.get("instructions").asText())
        val items = call(HttpMethod.GET, b, "/workspaces/$wsB/prompts/$promptB/refs/$refB/additional-input-items").body!!
        assertEquals(listOf("original"), items.get("items").map { it.get("message").asText() })
        assertEquals("k", call(HttpMethod.GET, b, "/workspaces/$wsB/llm-api-keys/$keyB").body!!.get("name").asText())
        assertEquals(200, call(HttpMethod.GET, b, "/workspaces/$wsB/llm-calls/$callLogB").status)
    }
}
