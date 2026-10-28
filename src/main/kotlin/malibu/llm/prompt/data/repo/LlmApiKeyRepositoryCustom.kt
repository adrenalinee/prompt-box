package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.entity.LlmApiKey
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface LlmApiKeyRepositoryCustom {
    /**
     * API 키 검색. null 인 조건은 무시한다. (name 은 공백도 무시, 대소문자 무시 부분일치)
     */
    fun searchApiKeys(
        searchSpec: LlmApiKeySearchSpec,
        pageable: Pageable,
    ): Page<LlmApiKey>

    /**
     * API 키가 속한 workspaceId. 없으면 null. (권한 검사용)
     */
    fun findWorkspaceIdById(apiKeyId: UUID): UUID?
}
