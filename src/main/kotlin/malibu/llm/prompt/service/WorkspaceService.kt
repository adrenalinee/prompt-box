package malibu.llm.prompt.service

import malibu.llm.prompt.data.dto.ifPresent
import malibu.llm.prompt.data.dto.Patch
import jakarta.persistence.EntityManager
import malibu.llm.prompt.authz.Roles
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.repo.RoleRepository
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.OrganizationRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceMemberRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.ModelNotFoundException
import malibu.llm.prompt.error.OrganizationNotFoundException
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
    private val organizationRepository: OrganizationRepository,
    private val roleRepository: RoleRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val entityManager: EntityManager,
) {

    /** accessibleUserId 가 있으면 그 사용자가 접근 가능한 workspace 만. (직접 할당 ∪ org 역할 함의, ACTIVE org) */
    @Transactional(readOnly = true)
    fun list(searchSpec: WorkspaceSearchSpec, pageable: Pageable, accessibleUserId: UUID? = null): Page<Workspace> {
        return workspaceRepository.searchWorkspaces(searchSpec, pageable, accessibleUserId)
    }

    @Transactional(readOnly = true)
    fun get(workspaceId: UUID): Workspace {
        return workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
    }

    /**
     * workspace 를 만든다. creatorUserId 가 있으면 같은 트랜잭션에서 WORKSPACE_ADMIN 으로 등록한다. (authz-spec §7)
     * creator 는 그 org 의 멤버여야 한다(PostgreSQL 복합 FK).
     */
    @Transactional
    fun create(
        organizationId: UUID,
        name: String,
        description: String?,
        defaultModelId: Long?,
        creatorUserId: UUID? = null,
    ): Workspace {
        val organization = organizationRepository.findById(organizationId)
            .orElseThrow { OrganizationNotFoundException(organizationId) }
        val defaultModel = if (defaultModelId != null) {
            llmModelRepository.findById(defaultModelId)
                .orElseThrow { ModelNotFoundException(defaultModelId) }
        } else {
            null
        }
        val workspace = workspaceRepository.save(
            Workspace(
                organization = organization,
                name = name,
                description = description,
                defaultModel = defaultModel,
            )
        )
        if (creatorUserId != null) {
            entityManager.persist(
                WorkspaceMember(
                    workspace = workspace,
                    user = entityManager.getReference(User::class.java, creatorUserId),
                    organizationId = organizationId,
                    role = roleRepository.findByCode(Roles.WORKSPACE_ADMIN)
                        ?: throw IllegalStateException("역할 시드가 없습니다: ${Roles.WORKSPACE_ADMIN}"),
                )
            )
        }
        return workspace
    }

    @Transactional
    fun update(
        workspaceId: UUID,
        name: String?,
        description: Patch<String?>,
        defaultModelId: Patch<Long?>,
    ): Workspace {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        if (!name.isNullOrBlank()) {
            workspace.name = name
        }
        description.ifPresent { workspace.description = it }
        defaultModelId.ifPresent { id ->
            workspace.defaultModel = id?.let {
                llmModelRepository.findById(it).orElseThrow { ModelNotFoundException(it) }
            }
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
        // PostgreSQL 은 workspace_member 가 cascade 되지만 H2(ddl-auto) 스키마에는 cascade 가 없다.
        workspaceMemberRepository.deleteByWorkspaceId(workspaceId)
        defaultLlmCallOptionsRepository.findByWorkspaceId(workspaceId)?.let { defaultLlmCallOptionsRepository.delete(it) }
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
