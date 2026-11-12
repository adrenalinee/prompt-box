package malibu.llm.prompt.api

import malibu.llm.prompt.authz.PlatformAuthz
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
@RestController
class MeController(
    private val userAccountService: UserAccountService,
    private val organizationService: OrganizationService,
    private val platformAuthz: PlatformAuthz,
) {

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    fun me(): MeResponse {
        val userId = CurrentUser.userId()
        val user = userAccountService.get(userId)
        return MeResponse(
            id = userId,
            email = user.email,
            name = user.name,
            status = user.status,
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
}
