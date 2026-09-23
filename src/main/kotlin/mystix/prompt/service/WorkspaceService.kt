package mystix.prompt.service

import mystix.prompt.data.entity.DefaultLlmCallOptions
import mystix.prompt.data.entity.Workspace
import mystix.prompt.data.repo.*
import mystix.prompt.error.ModelNotFoundException
import mystix.prompt.error.WorkspaceHasDependenciesException
import mystix.prompt.error.WorkspaceNotFoundException
import mystix.prompt.llm.ReasoningEffortType
import mystix.prompt.llm.SummaryType
import mystix.prompt.llm.VerbosityType
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
) {

    @Transactional(readOnly = true)
    fun list(name: String?, pageable: Pageable): Page<Workspace> {
        if (name.isNullOrBlank()) {
            return workspaceRepository.findAll(pageable)
        }
        return workspaceRepository.findByNameContainingIgnoreCase(name, pageable)
    }

    @Transactional(readOnly = true)
    fun get(workspaceId: UUID): Workspace {
        return workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
    }

    @Transactional
    fun create(name: String, description: String?, defaultModelId: Long?): Workspace {
        val defaultModel = if (defaultModelId != null) {
            llmModelRepository.findById(defaultModelId)
                .orElseThrow { ModelNotFoundException(defaultModelId) }
        } else {
            null
        }
        return workspaceRepository.save(
            Workspace(
                name = name,
                description = description,
                defaultModel = defaultModel,
            )
        )
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
