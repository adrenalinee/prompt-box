package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.AccessSummaryService
import malibu.llm.prompt.data.dto.AuthzCatalogResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 권한 / 역할 정의. 비밀이 아니므로 로그인한 사용자 모두에게 보여 준다.
 * 정의는 마이그레이션으로만 바뀌며 수정 API 는 없다. (authz-spec §3)
 */
@Tag(name = "권한 카탈로그", description = "시스템에 정의된 권한과 역할. 정의는 마이그레이션으로만 바뀌며 수정 API 는 없다.")
@RestController
class AuthzCatalogController(
    private val accessSummaryService: AccessSummaryService,
) {

    @GetMapping("/authz/catalog")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "권한 / 역할 카탈로그", description = "모든 권한과 역할, 역할별 권한을 돌려준다. org 역할은 함의하는 workspace 역할(impliedWorkspaceRole)도 함께 내려간다. 로그인한 사용자 누구나 볼 수 있다.")
    fun catalog(): AuthzCatalogResponse = accessSummaryService.catalog()
}
