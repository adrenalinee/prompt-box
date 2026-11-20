package malibu.llm.prompt.api

import malibu.llm.prompt.config.PermissionDocs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
 * API 문서의 "필요 권한" 문장. 모든 엔드포인트가 자동으로 풀리거나 @PermissionDoc 이 표현식과 맞아야 한다.
 */
class PermissionDocsTest {

    private fun endpoints(): List<Pair<Class<*>, Method>> {
        // @Profile("local") 컨트롤러(/dev/...)도 포함한다
        val environment = StandardEnvironment().apply { setActiveProfiles("local") }
        val scanner = ClassPathScanningCandidateComponentProvider(false, environment)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        return scanner.findCandidateComponents("malibu.llm.prompt")
            .map { Class.forName(it.beanClassName) }
            .filter { it.enclosingClass == null }
            .flatMap { type ->
                type.declaredMethods
                    .filter { AnnotatedElementUtils.findMergedAnnotation(it, RequestMapping::class.java) != null }
                    .map { type to it }
            }
    }

    @Test
    fun `모든 엔드포인트의 필요 권한 문장을 만들 수 있다`() {
        val all = endpoints()
        assertTrue(all.size > 40, "엔드포인트 스캔 결과가 너무 적다: ${all.size}")
        val problems = all.mapNotNull { (type, method) ->
            PermissionDocs.render(method, type).problem?.let { "${type.simpleName}.${method.name}: $it" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private fun render(name: String) = PermissionDocs.render(Samples::class.java.getMethod(name), Samples::class.java)

    @Test
    fun `표현식 종류별 문장`() {
        assertEquals("workspace 권한 `prompt:read`", render("ws").text)
        assertEquals("org 권한 `organization:read`", render("org").text)
        assertEquals("org 권한(workspace 가 속한 org 에서 판정) `workspace:delete`", render("orgForWs").text)
        assertEquals("플랫폼 권한 `llm_model:manage`", render("platform").text)
        assertEquals("로그인한 사용자 누구나.", render("authenticated").text)
        assertEquals("없음. 인증 없이 호출할 수 있다.", render("open").text)
        listOf("ws", "org", "orgForWs", "platform", "authenticated", "open").forEach { assertNull(render(it).problem, it) }
    }

    @Test
    fun `조건식은 @PermissionDoc 이 없으면 문제로 잡힌다`() {
        assertNotNull(render("conditional").problem)
        assertNull(render("conditionalDocumented").problem)
    }

    @Test
    fun `@PermissionDoc 에 표현식의 권한 코드가 빠지면 문제로 잡힌다`() {
        val problem = render("staleDoc").problem
        assertNotNull(problem)
        assertTrue(problem!!.contains("prompt_ref:publish"), problem)
    }

    @Suppress("unused")
    class Samples {
        @PreAuthorize("@wsAuthz.has(#workspaceId, 'prompt:read')") fun ws() {}
        @PreAuthorize("@orgAuthz.has(#organizationId, 'organization:read')") fun org() {}
        @PreAuthorize("@orgAuthz.hasForWorkspace(#workspaceId, 'workspace:delete')") fun orgForWs() {}
        @PreAuthorize("@platformAuthz.has('llm_model:manage')") fun platform() {}
        @PreAuthorize("isAuthenticated()") fun authenticated() {}
        fun open() {}

        @PreAuthorize("@wsAuthz.has(#workspaceId, 'prompt:write') and (#x or @wsAuthz.has(#workspaceId, 'prompt_ref:publish'))")
        fun conditional() {}

        @PreAuthorize("@wsAuthz.has(#workspaceId, 'prompt:write') and (#x or @wsAuthz.has(#workspaceId, 'prompt_ref:publish'))")
        @PermissionDoc("workspace 권한 `prompt:write`. x 가 아니면 `prompt_ref:publish` 도 필요하다.")
        fun conditionalDocumented() {}

        @PreAuthorize("@wsAuthz.has(#workspaceId, 'prompt:write') and (#x or @wsAuthz.has(#workspaceId, 'prompt_ref:publish'))")
        @PermissionDoc("workspace 권한 `prompt:write`.")
        fun staleDoc() {}
    }
}
