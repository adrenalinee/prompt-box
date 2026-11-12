package malibu.llm.prompt.authz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method

/**
 * 모든 엔드포인트에 권한 어노테이션이 있고, 그 권한이 authz-spec §4.5 매핑 표와 같은지 검사한다. (§8 4단계)
 *
 * 새 엔드포인트를 추가하면 이 표에도 추가해야 테스트가 통과한다. 권한을 붙이지 않은 엔드포인트는 실패한다.
 *
 * 표기(§4.5): ws = @wsAuthz.has, org = @orgAuthz.has, org(ws) = @orgAuthz.hasForWorkspace,
 * plat = @platformAuthz.has, 인증 = isAuthenticated(), permitAll = 어노테이션 없음(허용 목록)
 */
class EndpointAuthorizationMappingTest {

    private val expected: Map<String, String> = linkedMapOf(
        // 예외 (허용 목록)
        "GET /" to "permitAll",
        "POST /dev/token" to "permitAll",
        "POST /dev/init" to "인증",

        // 사용자 / 내 정보
        "GET /me" to "인증",
        "GET /admin/users" to "plat platform.user:manage",
        "POST /admin/users" to "plat platform.user:manage",
        "PATCH /admin/users/{userId}" to "plat platform.user:manage",

        // Organization
        "POST /admin/organizations" to "plat organization:create",
        "GET /admin/organizations" to "plat organization:list_all",
        "PATCH /admin/organizations/{organizationId}/status" to "plat organization:suspend",
        "GET /organizations" to "인증",
        "GET /organizations/{organizationId}" to "org organization:read",
        "GET /organizations/{organizationId}/my-permissions" to "org organization:read",
        "PATCH /organizations/{organizationId}" to "org organization:update",
        "DELETE /organizations/{organizationId}" to "org organization:delete",
        "GET /organizations/{organizationId}/members" to "org organization:read",
        "POST /organizations/{organizationId}/members" to "org organization.member:manage",
        "PATCH /organizations/{organizationId}/members/{userId}" to "org organization.member:manage",
        "DELETE /organizations/{organizationId}/members/{userId}" to "org organization.member:manage",
        "POST /organizations/{organizationId}/ownership-transfer" to "org organization.ownership:transfer",
        "GET /organizations/{organizationId}/workspaces" to "org organization:read",
        "POST /organizations/{organizationId}/workspaces" to "org workspace:create",

        // Workspace
        "GET /workspaces" to "인증",
        "GET /workspaces/{workspaceId}" to "ws workspace:read",
        "GET /workspaces/{workspaceId}/my-permissions" to "ws workspace:read",
        "PATCH /workspaces/{workspaceId}" to "ws workspace:update",
        "DELETE /workspaces/{workspaceId}" to "org(ws) workspace:delete",
        "GET /workspaces/{workspaceId}/members" to "ws workspace:read",
        "POST /workspaces/{workspaceId}/members" to "ws workspace.member:manage",
        "PATCH /workspaces/{workspaceId}/members/{userId}" to "ws workspace.member:manage",
        "DELETE /workspaces/{workspaceId}/members/{userId}" to "ws workspace.member:manage",
        "GET /workspaces/{workspaceId}/default-options" to "ws workspace:read",
        "PUT /workspaces/{workspaceId}/default-options" to "ws call_option:manage",
        "GET /workspaces/{workspaceId}/models" to "ws workspace:read",

        // Prompt
        "GET /workspaces/{workspaceId}/prompts" to "ws prompt:read",
        "GET /workspaces/{workspaceId}/prompts/{promptId}" to "ws prompt:read",
        "POST /workspaces/{workspaceId}/prompts" to "ws prompt:write",
        // latestTagRefId 를 바꾸면 publish 도 필요
        "PATCH /workspaces/{workspaceId}/prompts/{promptId}" to "ws prompt:write prompt_ref:publish",
        "DELETE /workspaces/{workspaceId}/prompts/{promptId}" to "ws prompt:delete",
        "GET /workspaces/{workspaceId}/prompts/{promptId}/refs" to "ws prompt:read",
        "GET /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}" to "ws prompt:read",
        // BRANCH: write, TAG: publish
        "POST /workspaces/{workspaceId}/prompts/{promptId}/refs" to "ws prompt:write prompt_ref:publish",
        "PATCH /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}" to "ws prompt:write",
        // TAG 이면 메서드 안에서 publish 를 추가로 검사한다
        "DELETE /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}" to "ws prompt:write",
        "GET /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}/additional-input-items" to "ws prompt:read",
        "POST /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}/additional-input-items" to "ws prompt:write",
        "PATCH /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}" to "ws prompt:write",
        "DELETE /workspaces/{workspaceId}/prompts/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}" to "ws prompt:write",

        // API Key / LLM 호출
        "GET /workspaces/{workspaceId}/llm-api-keys" to "ws llm:execute",
        "GET /workspaces/{workspaceId}/llm-api-keys/{apiKeyId}" to "ws llm:execute",
        "POST /workspaces/{workspaceId}/llm-api-keys" to "ws api_key:manage",
        "PATCH /workspaces/{workspaceId}/llm-api-keys/{apiKeyId}" to "ws api_key:manage",
        "DELETE /workspaces/{workspaceId}/llm-api-keys/{apiKeyId}" to "ws api_key:manage",
        "GET /workspaces/{workspaceId}/llm-calls" to "ws call_log:read",
        "GET /workspaces/{workspaceId}/llm-calls/{callLogId}" to "ws call_log:read",
        "POST /workspaces/{workspaceId}/llm-calls" to "ws llm:execute",
        "POST /workspaces/{workspaceId}/llm-calls/{callLogId}/cancel" to "ws llm:execute",

        // 플랫폼 전체 조회 (읽기 전용)
        "GET /admin/workspaces" to "plat platform.content:read",
        "GET /admin/prompts" to "plat platform.content:read",
        "GET /admin/prompts/{promptId}" to "plat platform.content:read",
        "GET /admin/prompts/{promptId}/refs" to "plat platform.content:read",
        "GET /admin/llm-calls" to "plat platform.content:read",
        "GET /admin/llm-calls/{callLogId}" to "plat platform.content:read",
        "GET /admin/llm-api-keys" to "plat platform.content:read",

        // 카탈로그
        "GET /models" to "인증",
        "GET /models/{modelId}" to "인증",
        "POST /models" to "plat llm_model:manage",
        "PATCH /models/{modelId}" to "plat llm_model:manage",
        "DELETE /models/{modelId}" to "plat llm_model:manage",
        "GET /llm-vendors" to "인증",
        "GET /llm-vendors/{vendorId}" to "인증",
        "POST /llm-vendors" to "plat llm_vendor:manage",
        "PATCH /llm-vendors/{vendorId}" to "plat llm_vendor:manage",
        "DELETE /llm-vendors/{vendorId}" to "plat llm_vendor:manage",
    )

    /** 어노테이션 없이 열어 두는 엔드포인트. (보안 필터에서 permitAll 또는 local 전용) */
    private val permitAllEndpoints = setOf("GET /", "POST /dev/token")

    private data class Endpoint(val key: String, val method: Method, val preAuthorize: String?)

    private fun endpoints(): List<Endpoint> {
        // @Profile("local") 컨트롤러(/dev/...)도 포함한다
        val environment = StandardEnvironment().apply { setActiveProfiles("local") }
        val scanner = ClassPathScanningCandidateComponentProvider(false, environment)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        return scanner.findCandidateComponents("malibu.llm.prompt")
            .map { Class.forName(it.beanClassName) }
            // 테스트 전용 컨트롤러(AuthzHttpTest.ProbeController 등)는 제외한다
            .filter { it.enclosingClass == null }
            .flatMap { type ->
                val classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping::class.java)
                val classPaths = classMapping?.path?.takeIf { it.isNotEmpty() }?.toList() ?: listOf("")
                val classPreAuthorize = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize::class.java)?.value
                type.declaredMethods.flatMap { method ->
                    val mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
                        ?: return@flatMap emptyList()
                    val preAuthorize = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize::class.java)?.value
                        ?: classPreAuthorize
                    val methodPaths = mapping.path.takeIf { it.isNotEmpty() }?.toList() ?: listOf("")
                    mapping.method.flatMap { httpMethod ->
                        classPaths.flatMap { classPath ->
                            methodPaths.map { methodPath ->
                                val path = (classPath + methodPath).ifEmpty { "/" }
                                Endpoint("${httpMethod.name} $path", method, preAuthorize)
                            }
                        }
                    }
                }
            }
    }

    /** "@wsAuthz.has(#workspaceId, 'prompt:read')" -> "ws prompt:read" */
    private fun describe(expression: String?): String {
        if (expression == null) return "permitAll"
        if (expression.trim() == "isAuthenticated()") return "인증"
        val beans = Regex("""@(\w+)\.(\w+)\(""").findAll(expression)
            .map { match ->
                when (match.groupValues[1] to match.groupValues[2]) {
                    "wsAuthz" to "has" -> "ws"
                    "orgAuthz" to "has" -> "org"
                    "orgAuthz" to "hasForWorkspace" -> "org(ws)"
                    "platformAuthz" to "has" -> "plat"
                    else -> "?${match.value}"
                }
            }
            .toSortedSet()
        val permissions = Regex("""'([^']+)'""").findAll(expression).map { it.groupValues[1] }.toSortedSet()
        return (beans + permissions).joinToString(" ")
    }

    @Test
    fun `모든 엔드포인트의 권한이 매핑 표와 같다`() {
        val actual = endpoints().associate { it.key to describe(it.preAuthorize) }

        val missingInCode = expected.keys - actual.keys
        val missingInTable = actual.keys - expected.keys
        val mismatched = expected.keys.intersect(actual.keys)
            .filter { expected[it] != actual[it] }
            .map { "$it: 표=${expected[it]}, 코드=${actual[it]}" }

        assertTrue(missingInCode.isEmpty(), "표에는 있지만 코드에 없는 엔드포인트: $missingInCode")
        assertTrue(missingInTable.isEmpty(), "표에 없는 엔드포인트(권한 확인 후 표에 추가): $missingInTable")
        assertTrue(mismatched.isEmpty(), "권한이 표와 다른 엔드포인트:\n${mismatched.joinToString("\n")}")
    }

    @Test
    fun `권한 어노테이션이 없는 엔드포인트는 허용 목록뿐이다`() {
        val unannotated = endpoints().filter { it.preAuthorize == null }.map { it.key }.toSet()
        assertEquals(permitAllEndpoints, unannotated)
    }

    @Test
    fun `어노테이션의 권한 문자열은 정의된 권한이고 판정 빈의 scope 와 맞다`() {
        val scopeByBean = mapOf(
            "wsAuthz" to AuthzScope.WORKSPACE,
            "orgAuthz" to AuthzScope.ORGANIZATION,
            "platformAuthz" to AuthzScope.PLATFORM,
        )
        val problems = endpoints().mapNotNull { endpoint ->
            val expression = endpoint.preAuthorize ?: return@mapNotNull null
            val beans = Regex("""@(\w+)\.""").findAll(expression).map { it.groupValues[1] }.toSet()
            val unknownBeans = beans - scopeByBean.keys
            val beanScopes = beans.mapNotNull { scopeByBean[it] }.toSet()
            val badPermissions = Regex("""'([^']+)'""").findAll(expression).map { it.groupValues[1] }
                .filter { permission ->
                    val definition = AuthzCatalog.permissions.firstOrNull { it.code == permission }
                    definition == null || definition.scope !in beanScopes
                }
                .toList()
            if (unknownBeans.isEmpty() && badPermissions.isEmpty()) null
            else "${endpoint.key}: 알 수 없는 빈=$unknownBeans, 잘못된 권한=$badPermissions"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
