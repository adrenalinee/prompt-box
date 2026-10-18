package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.LlmVendorSearchSpec
import malibu.llm.prompt.data.entity.LlmVendor
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface LlmVendorRepositoryCustom {
    /**
     * 벤더 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     */
    fun searchVendors(
        searchSpec: LlmVendorSearchSpec,
        pageable: Pageable,
    ): Page<LlmVendor>
}
