package malibu.llm.prompt.service

import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import malibu.llm.prompt.data.WorkspaceRole
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.repo.AppUserRepository
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceMemberRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.UserNotFoundException
import malibu.llm.prompt.error.ModelNotFoundException
import malibu.llm.prompt.error.WorkspaceHasDependenciesException
import malibu.llm.prompt.error.WorkspaceNotFoundException
import malibu.llm.streamclient.ReasoningEffortType
import malibu.llm.streamclient.SummaryType
import malibu.llm.streamclient.VerbosityType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val llmModelRepository: LlmModelRepository,
    private val promptRepository: PromptRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val appUserRepository: AppUserRepository,
) {

    /**
     * memberUserId 가 있으면 그 사용자가 멤버인 워크스페이스만 조회한다. (null 이면 전체)
     */
    @Transactional(readOnly = true)
    fun list(searchSpec: WorkspaceSearchSpec, pageable: Pageable, memberUserId: UUID? = null): Page<Workspace> {
        return workspaceRepository.searchWorkspaces(searchSpec, pageable, memberUserId)
    }

    @Transactional(readOnly = true)
    fun get(workspaceId: UUID): Workspace {
        return workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
    }

    /**
     * creatorUserId 가 있으면 생성자를 워크스페이스 ADMIN 멤버로 등록한다.
     */
    @Transactional
    fun create(name: String, description: String?, defaultModelId: Long?, creatorUserId: UUID? = null): Workspace {
        val defaultModel = if (defaultModelId != null) {
            llmModelRepository.findById(defaultModelId)
                .orElseThrow { ModelNotFoundException(defaultModelId) }
        } else {
            null
        }
        val workspace = workspaceRepository.save(
            Workspace(
                name = name,
                description = description,
                defaultModel = defaultModel,
            )
        )
        if (creatorUserId != null) {
            val creator = appUserRepository.findById(creatorUserId)
                .orElseThrow { UserNotFoundException(creatorUserId.toString()) }
            workspaceMemberRepository.save(
                WorkspaceMember(
                    workspace = workspace,
                    user = creator,
                    role = WorkspaceRole.ADMIN,
                )
            )
        }
        return workspace
    }

    @Transactional
    fun update(
        workspaceId: UUID,
        name: String?,
        description: String?,
        defaultModelId: Long?,
    ): Workspace {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        if (!name.isNullOrBlank()) {
            workspace.name = name
        }
        if (description != null) {
            workspace.description = description
        }
        if (defaultModelId != null) {
            val defaultModel = llmModelRepository.findById(defaultModelId)
                .orElseThrow { ModelNotFoundException(defaultModelId) }
            workspace.defaultModel = defaultModel
        }
        return workspaceRepository.save(workspace)
    }

    @Transactional
    fun delete(workspaceId: UUID) {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        val promptCount = promptRepository.countByWorkspaceId(workspaceId)
        val apiKeyCount = llmApiKeyRepository.countByWorkspaceId(workspaceId)
        val callLogCount = llmCallLogRepository.countByWorkspaceId(workspaceId)
        if (promptCount > 0 || apiKeyCount > 0 || callLogCount > 0) {
            throw WorkspaceHasDependenciesException()
        }
        // 멤버는 종속 데이터로 보지 않고 함께 지운다.
        workspaceMemberRepository.deleteByWorkspaceId(workspaceId)
        workspaceRepository.delete(workspace)
    }

    @Transactional(readOnly = true)
    fun getDefaultOptions(workspaceId: UUID): DefaultLlmCallOptions {
        return defaultLlmCallOptionsRepository.findByWorkspaceId(workspaceId)
            ?: throw WorkspaceNotFoundException(workspaceId)
    }

    @Transactional(readOnly = true)
    fun findDefaultOptions(workspaceId: UUID): DefaultLlmCallOptions? {
        return defaultLlmCallOptionsRepository.findByWorkspaceId(workspaceId)
    }

    @Transactional
    fun upsertDefaultOptions(
        workspaceId: UUID,
        temperature: Double?,
        topP: Double?,
        topK: Double?,
        includeThoughts: Boolean?,
        maxTokens: Long?,
        textFormat: String?,
        effort: ReasoningEffortType?,
        verbosity: VerbosityType?,
        summary: SummaryType?,
    ): DefaultLlmCallOptions {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }

        val existing = defaultLlmCallOptionsRepository.findByWorkspaceId(workspaceId)
        val options = if (existing == null) {
            DefaultLlmCallOptions(
                workspace = workspace,
                temperature = temperature,
                topP = topP,
                topK = topK,
                includeThoughts = includeThoughts,
                maxTokens = maxTokens,
                textFormat = textFormat,
                effort = effort,
                verbosity = verbosity,
                summary = summary,
            )
        } else {
            existing.temperature = temperature
            existing.topP = topP
            existing.topK = topK
            existing.includeThoughts = includeThoughts
            existing.maxTokens = maxTokens
            existing.textFormat = textFormat
            existing.effort = effort
            existing.verbosity = verbosity
            existing.summary = summary
            existing
        }

        return defaultLlmCallOptionsRepository.save(options)
    }
}
