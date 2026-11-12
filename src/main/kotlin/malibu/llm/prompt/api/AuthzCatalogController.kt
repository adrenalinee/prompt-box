package malibu.llm.prompt.api

import malibu.llm.prompt.authz.AccessSummaryService
import malibu.llm.prompt.data.dto.AuthzCatalogResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 권한 / 역할 정의. 비밀이 아니므로 로그인한 사용자 모두에게 보여 준다.
 * 정의는 마이그레이션으로만 바뀌며 수정 API 는 없다. (authz-spec §3)
 */
@RestController
class AuthzCatalogController(
    private val accessSummaryService: AccessSummaryService,
) {

    @GetMapping("/authz/catalog")
    @PreAuthorize("isAuthenticated()")
    fun catalog(): AuthzCatalogResponse = accessSummaryService.catalog()
}
