package malibu.llm.prompt.api

import malibu.llm.prompt.data.dto.AppUserResponse
import malibu.llm.prompt.data.dto.MeResponse
import malibu.llm.prompt.data.dto.MyMembershipResponse
import malibu.llm.prompt.data.dto.PlatformRoleUpdateRequest
import malibu.llm.prompt.data.entity.AppUser
import malibu.llm.prompt.security.AppUserPrincipal
import malibu.llm.prompt.service.AppUserService
import malibu.llm.prompt.service.WorkspaceMemberService
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class UsersController(
    private val appUserService: AppUserService,
    private val workspaceMemberService: WorkspaceMemberService,
) {

    /**
     * 내 정보, 플랫폼 롤, 워크스페이스 멤버십. 프론트에서 화면 권한 제어에 사용한다.
     */
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: AppUserPrincipal): MeResponse {
        val user = appUserService.get(principal.userId)
        val memberships = workspaceMemberService.listByUser(principal.userId).map {
            MyMembershipResponse(
                workspaceId = requireNotNull(it.workspace.id),
                workspaceName = it.workspace.name,
                role = it.role,
            )
        }
        return MeResponse(user = user.toResponse(), memberships = memberships)
    }

    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @GetMapping("/users")
    fun list(
        @RequestParam(required = false) email: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<AppUserResponse> =
        appUserService.list(email, pageable).toResponse { it.toResponse() }

    /**
     * 자신의 롤과 마지막 ADMIN 의 롤은 바꿀 수 없다.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @PatchMapping("/users/{userId}/platform-role")
    fun changePlatformRole(
        @PathVariable userId: UUID,
        @RequestBody request: PlatformRoleUpdateRequest,
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): AppUserResponse =
        appUserService.changePlatformRole(principal.userId, userId, request.platformRole).toResponse()
}

fun AppUser.toResponse() = AppUserResponse(
    id = requireNotNull(id),
    email = email,
    name = name,
    platformRole = platformRole,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
