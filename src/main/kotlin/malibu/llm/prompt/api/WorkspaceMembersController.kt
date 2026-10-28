package malibu.llm.prompt.api

import malibu.llm.prompt.data.dto.WorkspaceMemberCreateRequest
import malibu.llm.prompt.data.dto.WorkspaceMemberResponse
import malibu.llm.prompt.data.dto.WorkspaceMemberUpdateRequest
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.service.WorkspaceMemberService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 워크스페이스 멤버 관리. 조회는 GUEST 이상, 변경은 ADMIN 만 가능하다.
 * - 마지막 ADMIN 은 강등하거나 제거할 수 없다.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/members")
class WorkspaceMembersController(
    private val workspaceMemberService: WorkspaceMemberService,
) {

    @PreAuthorize("@wsAuthz.has(#workspaceId, 'GUEST')")
    @GetMapping
    fun list(@PathVariable workspaceId: UUID): List<WorkspaceMemberResponse> =
        workspaceMemberService.list(workspaceId).map { it.toResponse() }

    @PreAuthorize("@wsAuthz.has(#workspaceId, 'ADMIN')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @PathVariable workspaceId: UUID,
        @RequestBody request: WorkspaceMemberCreateRequest,
    ): WorkspaceMemberResponse =
        workspaceMemberService.add(workspaceId, request.userId, request.email, request.role).toResponse()

    @PreAuthorize("@wsAuthz.has(#workspaceId, 'ADMIN')")
    @PatchMapping("/{userId}")
    fun changeRole(
        @PathVariable workspaceId: UUID,
        @PathVariable userId: UUID,
        @RequestBody request: WorkspaceMemberUpdateRequest,
    ): WorkspaceMemberResponse =
        workspaceMemberService.changeRole(workspaceId, userId, request.role).toResponse()

    @PreAuthorize("@wsAuthz.has(#workspaceId, 'ADMIN')")
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable workspaceId: UUID,
        @PathVariable userId: UUID,
    ) {
        workspaceMemberService.remove(workspaceId, userId)
    }
}

private fun WorkspaceMember.toResponse() = WorkspaceMemberResponse(
    workspaceId = requireNotNull(workspace.id),
    userId = requireNotNull(user.id),
    email = user.email,
    name = user.name,
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
