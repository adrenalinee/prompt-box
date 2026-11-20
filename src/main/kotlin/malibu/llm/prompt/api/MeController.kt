package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.AccessSummaryService
import malibu.llm.prompt.authz.PlatformAuthz
import malibu.llm.prompt.data.dto.AccessSummaryResponse
import malibu.llm.prompt.data.dto.MeResponse
import malibu.llm.prompt.data.dto.MyOrganizationResponse
import malibu.llm.prompt.security.CurrentUser
import malibu.llm.prompt.service.OrganizationService
import malibu.llm.prompt.service.UserAccountService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 콘솔은 로그인 직후 GET /me 를 호출한다. 미등록/비활성 사용자는 인증 단계에서 403 이 된다. (authz-spec §10)
 */
@Tag(name = "내 정보", description = "로그인한 사용자 자신의 정보와 권한.")
@RestController
class MeController(
    private val userAccountService: UserAccountService,
    private val organizationService: OrganizationService,
    private val platformAuthz: PlatformAuthz,
    private val accessSummaryService: AccessSummaryService,
) {

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "내 정보", description = "사용자 정보, 플랫폼 역할 / 권한, 소속 org 목록. 콘솔은 로그인 직후 이 API 를 호출하고, 403(USER_NOT_REGISTERED / USER_DISABLED)이면 로그인을 거부한다.")
    fun me(): MeResponse {
        val userId = CurrentUser.userId()
        val user = userAccountService.get(userId)
        return MeResponse(
            id = userId,
            email = user.email,
            name = user.name,
            status = user.status,
            linked = user.authProvider != null,
            createdAt = user.createdAt,
            platformRoles = userAccountService.platformRoles(userId),
            platformPermissions = platformAuthz.permissions().sorted(),
            organizations = organizationService.listMemberships(userId).map {
                MyOrganizationResponse(
                    id = requireNotNull(it.organization.id),
                    name = it.organization.name,
                    status = it.organization.status,
                    role = it.role.code,
                )
            },
        )
    }

    /** 내 역할과 권한 요약(플랫폼 / org / workspace). 본인 것만 돌려준다. */
    @GetMapping("/me/access")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "내 역할·권한 요약", description = "플랫폼 / org / workspace 별 내 역할과 권한.")
    fun access(): AccessSummaryResponse = accessSummaryService.summarize(CurrentUser.userId())
}
