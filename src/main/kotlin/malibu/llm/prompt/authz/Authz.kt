package malibu.llm.prompt.authz

import malibu.llm.prompt.error.AuthzNotFoundException
import malibu.llm.prompt.security.CurrentUser
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import java.util.UUID

/**
 * 판정 결과. (authz-spec §4.2)
 * - NOT_MEMBER: 404 (리소스 존재 여부를 노출하지 않음)
 * - DENIED: 403
 */
enum class AuthzDecision {
    NOT_MEMBER,
    DENIED,
    GRANTED,
}

/**
 * 권한 집합 조회 + 요청 범위 메모이제이션. (authz-spec §4.4 1차 캐시)
 * HTTP 요청 밖(테스트, 배치)에서는 메모이제이션 없이 매번 조회한다.
 */
@Component
class PermissionResolver(
    private val queryRepository: AuthzQueryRepository,
) {
    fun platform(userId: UUID): Set<String> =
        memo("platform:$userId") { queryRepository.platformPermissions(userId) }

    fun organization(organizationId: UUID, userId: UUID): Set<String> =
        memo("org:$organizationId:$userId") { queryRepository.organizationPermissions(organizationId, userId) }

    fun organizationByWorkspace(workspaceId: UUID, userId: UUID): Set<String> =
        memo("org-ws:$workspaceId:$userId") { queryRepository.organizationPermissionsByWorkspace(workspaceId, userId) }

    fun workspace(workspaceId: UUID, userId: UUID): Set<String> =
        memo("ws:$workspaceId:$userId") { queryRepository.workspacePermissions(workspaceId, userId) }

    /** 같은 요청 안에서 멤버십을 바꾼 뒤 다시 판정해야 할 때 호출한다. */
    fun evictRequestCache() {
        RequestContextHolder.getRequestAttributes()?.removeAttribute(REQUEST_CACHE_KEY, RequestAttributes.SCOPE_REQUEST)
    }

    @Suppress("UNCHECKED_CAST")
    private fun memo(key: String, load: () -> Set<String>): Set<String> {
        val attributes = RequestContextHolder.getRequestAttributes() ?: return load()
        val cache = (attributes.getAttribute(REQUEST_CACHE_KEY, RequestAttributes.SCOPE_REQUEST) as? MutableMap<String, Set<String>>)
            ?: HashMap<String, Set<String>>().also {
                attributes.setAttribute(REQUEST_CACHE_KEY, it, RequestAttributes.SCOPE_REQUEST)
            }
        return cache.getOrPut(key, load)
    }

    companion object {
        private val REQUEST_CACHE_KEY = PermissionResolver::class.java.name + ".cache"

        /** 결과가 비어 있으면 NOT_MEMBER. */
        fun decide(permissions: Set<String>, permission: String): AuthzDecision = when {
            permissions.isEmpty() -> AuthzDecision.NOT_MEMBER
            permission in permissions -> AuthzDecision.GRANTED
            else -> AuthzDecision.DENIED
        }

        /** @PreAuthorize 용: NOT_MEMBER 는 404 예외, DENIED 는 false(403). */
        fun toPreAuthorizeResult(decision: AuthzDecision): Boolean = when (decision) {
            AuthzDecision.NOT_MEMBER -> throw AuthzNotFoundException()
            AuthzDecision.DENIED -> false
            AuthzDecision.GRANTED -> true
        }

        /** 권한 문자열 오타 / scope 혼동을 바로 드러낸다. */
        fun requireScope(permission: String, expected: AuthzScope) {
            val actual = AuthzCatalog.scopeOf(permission)
            require(actual == expected) { "$permission 은 $actual 권한입니다. $expected 판정에 쓸 수 없습니다." }
        }
    }
}

/**
 * `@PreAuthorize("@platformAuthz.has('llm_model:manage')")`
 *
 * PLATFORM 판정에는 NOT_MEMBER 가 없다. 권한이 없으면 403.
 */
@Component("platformAuthz")
class PlatformAuthz(
    private val resolver: PermissionResolver,
) {
    fun has(permission: String): Boolean {
        PermissionResolver.requireScope(permission, AuthzScope.PLATFORM)
        return permission in resolver.platform(CurrentUser.userId())
    }

    fun permissions(): Set<String> = resolver.platform(CurrentUser.userId())
}

/**
 * `@PreAuthorize("@orgAuthz.has(#organizationId, 'workspace:create')")`
 * `@PreAuthorize("@orgAuthz.hasForWorkspace(#workspaceId, 'workspace:delete')")`
 */
@Component("orgAuthz")
class OrgAuthz(
    private val resolver: PermissionResolver,
) {
    fun decide(organizationId: UUID, permission: String): AuthzDecision {
        PermissionResolver.requireScope(permission, AuthzScope.ORGANIZATION)
        return PermissionResolver.decide(resolver.organization(organizationId, CurrentUser.userId()), permission)
    }

    fun decideForWorkspace(workspaceId: UUID, permission: String): AuthzDecision {
        PermissionResolver.requireScope(permission, AuthzScope.ORGANIZATION)
        return PermissionResolver.decide(resolver.organizationByWorkspace(workspaceId, CurrentUser.userId()), permission)
    }

    fun has(organizationId: UUID, permission: String): Boolean =
        PermissionResolver.toPreAuthorizeResult(decide(organizationId, permission))

    fun hasForWorkspace(workspaceId: UUID, permission: String): Boolean =
        PermissionResolver.toPreAuthorizeResult(decideForWorkspace(workspaceId, permission))

    /** my-permissions 용. 멤버가 아니면 404. */
    fun permissions(organizationId: UUID): Set<String> =
        resolver.organization(organizationId, CurrentUser.userId()).ifEmpty { throw AuthzNotFoundException() }
}

/**
 * `@PreAuthorize("@wsAuthz.has(#workspaceId, 'prompt:write')")`
 *
 * workspace 권한 = 직접 할당 ∪ org 역할의 함의. PLATFORM_ADMIN 이라고 통과시키지 않는다.
 */
@Component("wsAuthz")
class WorkspaceAuthz(
    private val resolver: PermissionResolver,
) {
    fun decide(workspaceId: UUID, permission: String): AuthzDecision {
        PermissionResolver.requireScope(permission, AuthzScope.WORKSPACE)
        return PermissionResolver.decide(resolver.workspace(workspaceId, CurrentUser.userId()), permission)
    }

    fun has(workspaceId: UUID, permission: String): Boolean =
        PermissionResolver.toPreAuthorizeResult(decide(workspaceId, permission))

    /** my-permissions 용. 멤버가 아니면 404. */
    fun permissions(workspaceId: UUID): Set<String> =
        resolver.workspace(workspaceId, CurrentUser.userId()).ifEmpty { throw AuthzNotFoundException() }
}
