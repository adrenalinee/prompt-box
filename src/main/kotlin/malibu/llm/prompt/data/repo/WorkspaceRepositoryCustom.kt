package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.Workspace
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface WorkspaceRepositoryCustom {
    /**
     * 워크스페이스 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     * - memberUserId 가 있으면 그 사용자가 멤버인 워크스페이스만 조회한다.
     *   클라이언트가 조작할 수 없도록 searchSpec 이 아닌 별도 파라미터로 받는다.
     */
    fun searchWorkspaces(
        searchSpec: WorkspaceSearchSpec,
        pageable: Pageable,
        memberUserId: UUID? = null,
    ): Page<Workspace>
}
