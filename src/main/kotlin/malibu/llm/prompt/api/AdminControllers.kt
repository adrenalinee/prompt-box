package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import malibu.llm.prompt.authz.AccessSummaryService
import malibu.llm.prompt.authz.Permissions
import malibu.llm.prompt.data.dto.AccessSummaryResponse
import malibu.llm.prompt.data.PromptRefType
import malibu.llm.prompt.data.dto.LlmApiKeyResponse
import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.dto.LlmCallLogDetailResponse
import malibu.llm.prompt.data.dto.LlmCallLogResponse
import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.dto.OrganizationCreateRequest
import malibu.llm.prompt.data.dto.OrganizationResponse
import malibu.llm.prompt.data.dto.OrganizationStatusRequest
import malibu.llm.prompt.data.dto.PromptRefResponse
import malibu.llm.prompt.data.dto.PromptRefSearchSpec
import malibu.llm.prompt.data.dto.PromptResponse
import malibu.llm.prompt.data.dto.PromptSearchSpec
import malibu.llm.prompt.data.dto.UserCreateRequest
import malibu.llm.prompt.data.dto.UserResponse
import malibu.llm.prompt.data.dto.UserUpdateRequest
import malibu.llm.prompt.data.dto.WorkspaceResponse
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.security.CurrentUser
import malibu.llm.prompt.service.LlmCallLogService
import malibu.llm.prompt.service.LlmCatalogService
import malibu.llm.prompt.service.OrganizationService
import malibu.llm.prompt.service.PromptService
import malibu.llm.prompt.service.UserAccountService
import malibu.llm.prompt.service.WorkspaceService
import org.springdoc.core.annotations.ParameterObject
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 플랫폼 관리: 사용자. (authz-spec §10 관리자 사전 등록)
 */
@Tag(name = "관리: 사용자", description = "플랫폼 관리자가 사용자를 사전 등록하고 상태와 플랫폼 역할을 관리한다. 서버에는 자동 가입이 없으므로 여기서 등록한 사용자만 로그인할 수 있다.")
@RestController
@RequestMapping("/admin/users")
class AdminUsersController(
    private val userAccountService: UserAccountService,
    private val accessSummaryService: AccessSummaryService,
) {

    /** query: email / name 부분일치 */
    @GetMapping
    @PreAuthorize("@platformAuthz.has('${Permissions.PLATFORM_USER_MANAGE}')")
    @Operation(summary = "사용자 목록", description = "query 로 email 또는 name 을 부분일치 검색한다.")
    fun list(
        @RequestParam(required = false) query: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<UserResponse> =
        userAccountService.list(query, pageable).toResponse { it.toResponse() }

    @GetMapping("/{userId}")
    @PreAuthorize("@platformAuthz.has('${Permissions.PLATFORM_USER_MANAGE}')")
    @Operation(summary = "사용자 조회")
    fun get(@PathVariable userId: UUID): UserResponse = userAccountService.get(userId).toResponse()

    /**
     * 그 사용자의 역할과 권한 요약. 사용자 관리 정보이므로 platform.content:read 가 아니라 platform.user:manage 로 본다.
     * 보여 주기만 하며, 관리자가 그 org / workspace 에 접근할 권한이 생기지 않는다.
     */
    @GetMapping("/{userId}/access")
    @PreAuthorize("@platformAuthz.has('${Permissions.PLATFORM_USER_MANAGE}')")
    @Operation(summary = "사용자의 역할·권한 요약", description = "그 사용자의 플랫폼 / org / workspace 역할과 권한을 보여 준다. 보여 주기만 하며, 관리자가 그 org / workspace 에 접근할 권한이 생기지는 않는다.")
    fun access(@PathVariable userId: UUID): AccessSummaryResponse {
        userAccountService.get(userId) // 없는 사용자는 404
        return accessSummaryService.summarize(userId)
    }

    /** 사전 등록. 개인 org(ORG_OWNER) + 기본 workspace 가 함께 만들어진다. 첫 로그인 때 Google 계정이 연결된다. */
    @PostMapping
    @PreAuthorize("@platformAuthz.has('${Permissions.PLATFORM_USER_MANAGE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "사용자 사전 등록", description = "개인 org(ORG_OWNER)와 기본 workspace 가 함께 만들어진다. 사용자가 처음 Google 로 로그인할 때 email 로 계정이 연결된다.")
    fun register(@RequestBody request: UserCreateRequest): UserResponse =
        userAccountService.createUser(request.email, request.name).toResponse()

    /** 이름 / 상태 / 플랫폼 역할. 자기 자신을 비활성화하거나 자신의 PLATFORM_ADMIN 을 회수할 수 없다. */
    @PatchMapping("/{userId}")
    @PreAuthorize("@platformAuthz.has('${Permissions.PLATFORM_USER_MANAGE}')")
    @Operation(summary = "사용자 수정", description = "이름, 상태, 플랫폼 역할을 바꾼다. platformRoles 를 보내면 그 목록으로 전체를 교체한다. 자기 자신을 비활성화하거나 자신의 PLATFORM_ADMIN 을 회수하면 400.")
    fun update(
        @PathVariable userId: UUID,
        @RequestBody request: UserUpdateRequest,
    ): UserResponse =
        userAccountService.update(
            actorUserId = CurrentUser.userId(),
            userId = userId,
            name = request.name,
            status = request.status,
            platformRoles = request.platformRoles,
        ).toResponse()

    private fun User.toResponse() = UserResponse(
        id = requireNotNull(id),
        email = email,
        name = name,
        status = status,
        linked = authProvider != null,
        platformRoles = userAccountService.platformRoles(requireNotNull(id)),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/**
 * 플랫폼 관리: org 생성 / 전체 목록 / 정지.
 */
@Tag(name = "관리: Organization", description = "플랫폼 관리자가 org 를 만들고, 전체 org 를 조회하고, 정지한다.")
@RestController
@RequestMapping("/admin/organizations")
class AdminOrganizationsController(
    private val organizationService: OrganizationService,
) {

    /** ownerUserId 를 생략하면 호출한 사용자가 ORG_OWNER 가 된다. */
    @PostMapping
    @PreAuthorize("@platformAuthz.has('${Permissions.ORGANIZATION_CREATE}')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Organization 생성", description = "ownerUserId 를 생략하면 호출한 사용자가 ORG_OWNER 가 된다.")
    fun create(@RequestBody request: OrganizationCreateRequest): OrganizationResponse =
        organizationService.create(request.name, request.ownerUserId ?: CurrentUser.userId()).toResponse()

    @GetMapping
    @PreAuthorize("@platformAuthz.has('${Permissions.ORGANIZATION_LIST_ALL}')")
    @Operation(summary = "전체 Organization 목록", description = "name 으로 검색한다.")
    fun list(
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<OrganizationResponse> =
        organizationService.listAll(name, pageable).toResponse { it.toResponse() }

    /** ACTIVE ↔ SUSPENDED */
    @PatchMapping("/{organizationId}/status")
    @PreAuthorize("@platformAuthz.has('${Permissions.ORGANIZATION_SUSPEND}')")
    @Operation(summary = "Organization 정지 / 재개", description = "ACTIVE ↔ SUSPENDED. SUSPENDED org 와 그 아래 workspace 는 멤버도 접근할 수 없다(404).")
    fun updateStatus(
        @PathVariable organizationId: UUID,
        @RequestBody request: OrganizationStatusRequest,
    ): OrganizationResponse =
        organizationService.updateStatus(organizationId, request.status).toResponse()
}

/**
 * 플랫폼 전체 조회(읽기 전용). 모든 workspace 의 콘텐츠를 본다.
 * workspace 판정(wsAuthz)에는 PLATFORM_ADMIN 우회가 없으므로 이 경로로만 제공한다. (authz-spec §1)
 */
@Tag(name = "관리: 콘텐츠 조회", description = "플랫폼 전체의 workspace / 프롬프트 / 호출 로그 / API 키를 읽기 전용으로 조회한다. PLATFORM_ADMIN 이라도 workspace API(/workspaces/...)로는 멤버가 아닌 workspace 에 접근할 수 없으므로, 플랫폼 차원의 조회는 이 경로로만 한다.")
@RestController
@RequestMapping("/admin")
@PreAuthorize("@platformAuthz.has('${Permissions.PLATFORM_CONTENT_READ}')")
class AdminContentController(
    private val workspaceService: WorkspaceService,
    private val promptService: PromptService,
    private val llmCallLogService: LlmCallLogService,
    private val llmCatalogService: LlmCatalogService,
) {

    @GetMapping("/workspaces")
    @Operation(summary = "전체 workspace 목록")
    fun listWorkspaces(
        @ParameterObject searchSpec: WorkspaceSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<WorkspaceResponse> =
        workspaceService.list(searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/prompts")
    @Operation(summary = "전체 프롬프트 목록")
    fun listPrompts(
        @ParameterObject searchSpec: PromptSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptResponse> =
        promptService.listPrompts(searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/prompts/{promptId}")
    @Operation(summary = "프롬프트 조회")
    fun getPrompt(@PathVariable promptId: UUID): PromptResponse =
        promptService.getPromptForPlatform(promptId).toResponse()

    @GetMapping("/prompts/{promptId}/refs")
    @Operation(summary = "프롬프트 ref 목록", description = "type(BRANCH / TAG), name 으로 거른다.")
    fun listPromptRefs(
        @PathVariable promptId: UUID,
        @RequestParam(required = false) type: PromptRefType?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptRefResponse> =
        promptService.listRefsForPlatform(PromptRefSearchSpec(promptId = promptId, type = type, name = name), pageable)
            .toResponse { it.toResponse() }

    @GetMapping("/llm-calls")
    @Operation(summary = "전체 LLM 호출 로그 목록")
    fun listLlmCalls(
        @ParameterObject searchSpec: LlmCallSearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmCallLogResponse> =
        llmCallLogService.listLogs(searchSpec, pageable).toResponse { it.toResponse() }

    @GetMapping("/llm-calls/{callLogId}")
    @Operation(summary = "LLM 호출 로그 상세", description = "호출 정보와 입력 / 출력 item, 토큰 사용량을 포함한다.")
    fun getLlmCall(@PathVariable callLogId: UUID): LlmCallLogDetailResponse =
        llmCallLogService.getLogForPlatform(callLogId).toResponse()

    /** 값은 last5 만 내려간다. */
    @GetMapping("/llm-api-keys")
    @Operation(summary = "전체 API 키 목록", description = "키 값은 내려가지 않고 끝 5자리(last5)만 내려간다.")
    fun listApiKeys(
        @ParameterObject searchSpec: LlmApiKeySearchSpec,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LlmApiKeyResponse> =
        llmCatalogService.listApiKeys(searchSpec, pageable).toResponse { it.toResponse() }
}
