package malibu.llm.prompt.config

import io.swagger.v3.oas.models.Operation
import malibu.llm.prompt.api.PermissionDoc
import mu.KotlinLogging
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.method.HandlerMethod
import java.lang.reflect.Method

/**
 * 엔드포인트의 @PreAuthorize 로 API 문서에 "필요 권한" 문장을 붙인다.
 * 권한 표현식이 바뀌면 문서도 같이 바뀌므로 손으로 맞출 필요가 없다.
 *
 * - 어노테이션 없음: 인증 없이 호출(보안 필터의 permitAll. EndpointAuthorizationMappingTest 가 허용 목록을 검사한다)
 * - `isAuthenticated()`: 로그인한 사용자 누구나
 * - `@wsAuthz.has(#workspaceId, 'x')` 같은 판정 한 번: scope 와 권한 코드
 * - 그 밖(조건식 등)은 자동으로 풀지 않는다. @PermissionDoc 으로 문장을 직접 쓴다.
 */
object PermissionDocs {

    const val HEADING = "**필요 권한**"

    data class Result(val text: String, val problem: String? = null)

    private val singleCheck = Regex("""^@(\w+)\.(\w+)\((?:#\w+,\s*)?'([^']+)'\)$""")
    private val permissionCode = Regex("""'([^']+)'""")

    private val scopes = mapOf(
        ("wsAuthz" to "has") to "workspace 권한",
        ("orgAuthz" to "has") to "org 권한",
        ("orgAuthz" to "hasForWorkspace") to "org 권한(workspace 가 속한 org 에서 판정)",
        ("platformAuthz" to "has") to "플랫폼 권한",
    )

    fun preAuthorize(method: Method, beanType: Class<*>): String? =
        (AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize::class.java)
            ?: AnnotatedElementUtils.findMergedAnnotation(beanType, PreAuthorize::class.java))?.value

    fun render(method: Method, beanType: Class<*>): Result {
        val expression = preAuthorize(method, beanType)?.trim()
        val override = AnnotatedElementUtils.findMergedAnnotation(method, PermissionDoc::class.java)?.value

        if (override != null) {
            val codes = expression?.let { permissionCode.findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()
            val missing = codes.filterNot { "`$it`" in override }
            return Result(
                override,
                missing.takeIf { it.isNotEmpty() }?.let { "@PermissionDoc 에 권한 코드가 빠졌다: $it (표현식: $expression)" },
            )
        }
        if (expression == null) return Result("없음. 인증 없이 호출할 수 있다.")
        if (expression == "isAuthenticated()") return Result("로그인한 사용자 누구나.")

        val match = singleCheck.matchEntire(expression)
        val scope = match?.let { scopes[it.groupValues[1] to it.groupValues[2]] }
        if (match != null && scope != null) {
            return Result("$scope `${match.groupValues[3]}`")
        }
        return Result(
            "`$expression`",
            "자동으로 풀 수 없는 표현식이다. @PermissionDoc 으로 문장을 써야 한다: $expression",
        )
    }
}

@Configuration
class PermissionDocsConfig {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun permissionDocsCustomizer(): OperationCustomizer = OperationCustomizer { operation: Operation, handlerMethod: HandlerMethod ->
        val result = PermissionDocs.render(handlerMethod.method, handlerMethod.beanType)
        result.problem?.let { logger.warn { "${handlerMethod.shortLogMessage}: $it" } }
        val line = "${PermissionDocs.HEADING}: ${result.text}"
        operation.description = operation.description?.takeIf { it.isNotBlank() }?.let { "$it\n\n$line" } ?: line
        operation
    }
}
