package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.Workspace
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface WorkspaceRepositoryCustom {
    /**
     * 워크스페이스 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     */
    fun searchWorkspaces(
        searchSpec: WorkspaceSearchSpec,
        pageable: Pageable,
    ): Page<Workspace>
}
