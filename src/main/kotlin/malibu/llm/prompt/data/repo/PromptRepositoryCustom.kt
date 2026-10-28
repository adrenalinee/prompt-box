package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.PromptSearchSpec
import malibu.llm.prompt.data.entity.Prompt
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface PromptRepositoryCustom {
    /**
     * 프롬프트 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     */
    fun searchPrompts(
        searchSpec: PromptSearchSpec,
        pageable: Pageable,
    ): Page<Prompt>

    /**
     * 프롬프트가 속한 workspaceId. 없으면 null. (권한 검사용)
     */
    fun findWorkspaceIdById(promptId: UUID): UUID?
}
