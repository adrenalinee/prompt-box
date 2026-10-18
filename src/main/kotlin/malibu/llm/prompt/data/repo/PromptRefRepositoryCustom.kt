package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.PromptRefSearchSpec
import malibu.llm.prompt.data.entity.PromptRef
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface PromptRefRepositoryCustom {
    /**
     * 프롬프트 ref 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     */
    fun searchRefs(
        searchSpec: PromptRefSearchSpec,
        pageable: Pageable,
    ): Page<PromptRef>
}
