package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.Workspace
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface WorkspaceRepositoryCustom {
    /**
     * 워크스페이스 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     *
     * accessibleUserId 가 있으면 그 사용자가 접근 가능한 workspace 만 찾는다. (authz-spec §4.3 workspace 권한과 같은 기준)
     * - ACTIVE org 의 workspace 이고
     * - workspace_member 로 직접 할당되었거나, org 역할이 workspace 역할을 함의한다.
     */
    fun searchWorkspaces(
        searchSpec: WorkspaceSearchSpec,
        pageable: Pageable,
        accessibleUserId: UUID? = null,
    ): Page<Workspace>
}
