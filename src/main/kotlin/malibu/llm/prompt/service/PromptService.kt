package malibu.llm.prompt.service

import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.PromptRefType
import malibu.llm.prompt.data.dto.PromptRefSearchSpec
import malibu.llm.prompt.data.dto.PromptSearchSpec
import malibu.llm.prompt.data.entity.AdditionalInputItem
import malibu.llm.prompt.data.entity.Prompt
import malibu.llm.prompt.data.entity.PromptRef
import malibu.llm.prompt.data.repo.AdditionalInputItemRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.PromptRefRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.AdditionalInputLimitExceededException
import malibu.llm.prompt.error.AdditionalInputNotFoundException
import malibu.llm.prompt.error.CannotDeleteDefaultBranchException
import malibu.llm.prompt.error.CannotDeleteLatestTagException
import malibu.llm.prompt.error.DefaultBranchMustBeBranchException
import malibu.llm.prompt.error.LatestTagMustBeTagException
import malibu.llm.prompt.error.PromptHasCallLogsException
import malibu.llm.prompt.error.PromptNotFoundException
import malibu.llm.prompt.error.PromptRefLimitExceededException
import malibu.llm.prompt.error.PromptRefNotFoundException
import malibu.llm.prompt.error.RefHasCallLogsException
import malibu.llm.prompt.error.TagImmutableException
import malibu.llm.prompt.error.TagRequiresFromRefException
import malibu.llm.prompt.error.WorkspaceNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * IDOR 방어(authz-spec §5):
 * - prompt 는 workspaceId 조건으로만 조회한다.
 * - ref / additional input 은 루트(prompt → ref)를 거쳐서만 조회한다.
 * - 다른 workspace / 다른 prompt / 다른 ref 의 ID 는 모두 404 다.
 */
@Service
class PromptService(
    private val workspaceRepository: WorkspaceRepository,
    private val promptRepository: PromptRepository,
    private val promptRefRepository: PromptRefRepository,
    private val additionalInputItemRepository: AdditionalInputItemRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
) {

    // ---- 플랫폼 전체 조회 (/admin/..., 읽기 전용) ----

    /** workspaceId 가 없으면 모든 workspace. 플랫폼 조회와 workspace 경로 목록이 함께 쓴다. */
    @Transactional(readOnly = true)
    fun listPrompts(
        searchSpec: PromptSearchSpec,
        pageable: Pageable,
    ): Page<Prompt> {
        searchSpec.workspaceId?.let { workspaceId ->
            if (!workspaceRepository.existsById(workspaceId)) {
                throw WorkspaceNotFoundException(workspaceId)
            }
        }
        return promptRepository.searchPrompts(searchSpec, pageable)
    }

    @Transactional(readOnly = true)
    fun getPromptForPlatform(promptId: UUID): Prompt =
        promptRepository.findById(promptId).orElseThrow { PromptNotFoundException(promptId) }

    @Transactional(readOnly = true)
    fun listRefsForPlatform(searchSpec: PromptRefSearchSpec, pageable: Pageable): Page<PromptRef> {
        searchSpec.promptId?.let { getPromptForPlatform(it) }
        return promptRefRepository.searchRefs(searchSpec, pageable)
    }

    // ---- prompt ----

    @Transactional(readOnly = true)
    fun getPrompt(workspaceId: UUID, promptId: UUID): Prompt = requirePrompt(workspaceId, promptId)

    @Transactional
    fun updatePrompt(
        workspaceId: UUID,
        promptId: UUID,
        name: String?,
        description: String?,
        defaultBranchRefId: Long?,
        latestTagRefId: Long?,
    ): Prompt {
        val prompt = requirePrompt(workspaceId, promptId)
        if (!name.isNullOrBlank()) {
            prompt.name = name
        }
        if (description != null) {
            prompt.description = description
        }
        if (defaultBranchRefId != null) {
            val ref = requireRefOf(prompt, defaultBranchRefId)
            if (ref.type != PromptRefType.BRANCH) {
                throw DefaultBranchMustBeBranchException()
            }
            prompt.defaultBranch = ref
        }
        if (latestTagRefId != null) {
            val ref = requireRefOf(prompt, latestTagRefId)
            if (ref.type != PromptRefType.TAG) {
                throw LatestTagMustBeTagException()
            }
            prompt.latestTag = ref
        }
        return promptRepository.save(prompt)
    }

    @Transactional
    fun deletePrompt(workspaceId: UUID, promptId: UUID) {
        val prompt = requirePrompt(workspaceId, promptId)
        val refs = promptRefRepository.findByPromptId(promptId)

        val hasRefLogs = refs.any { ref ->
            llmCallLogRepository.countByPromptRefId(ref.id) > 0
        }
        if (hasRefLogs) {
            throw PromptHasCallLogsException()
        }

        refs.forEach { ref ->
            additionalInputItemRepository.deleteByPromptRefId(ref.id)
        }
        if (refs.isNotEmpty()) {
            promptRefRepository.deleteAll(refs)
        }
        promptRepository.delete(prompt)
    }

    @Transactional
    fun createPrompt(
        workspaceId: UUID,
        name: String,
        description: String? = null,
        instructions: String,
        additionalInputs: List<AdditionalInputItemSpec>,
        mainRefName: String,
    ): Prompt {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }

        val prompt = promptRepository.save(
            Prompt(
                name = name,
                description = description,
                workspace = workspace,
            )
        )

        val ref = promptRefRepository.save(
            PromptRef(
                prompt = prompt,
                name = mainRefName,
                type = PromptRefType.BRANCH,
                instructions = instructions,
                description = "default branch",
            )
        )

        val orderedAdditionalInputs = additionalInputs
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<AdditionalInputItemSpec>> { it.value.position ?: Int.MAX_VALUE }
                    .thenBy { it.index }
            )
            .mapIndexed { index, entry ->
                AdditionalInputItem(
                    position = index + 1,
                    role = entry.value.role,
                    promptRef = ref,
                    message = entry.value.message,
                )
            }
        additionalInputItemRepository.saveAll(orderedAdditionalInputs)

        prompt.defaultBranch = ref
        return promptRepository.save(prompt)
    }

    // ---- ref ----

    @Transactional(readOnly = true)
    fun getRef(workspaceId: UUID, promptId: UUID, refId: Long): PromptRef =
        requireRef(workspaceId, promptId, refId)

    @Transactional(readOnly = true)
    fun listRefs(
        workspaceId: UUID,
        promptId: UUID,
        type: PromptRefType?,
        name: String?,
        pageable: Pageable,
    ): Page<PromptRef> {
        requirePrompt(workspaceId, promptId)
        return promptRefRepository.searchRefs(PromptRefSearchSpec(promptId = promptId, type = type, name = name), pageable)
    }

    @Transactional
    fun deleteRef(workspaceId: UUID, promptId: UUID, refId: Long) {
        val prompt = requirePrompt(workspaceId, promptId)
        val ref = requireRefOf(prompt, refId)
        if (prompt.defaultBranch?.id == ref.id) {
            throw CannotDeleteDefaultBranchException()
        }
        if (prompt.latestTag?.id == ref.id) {
            throw CannotDeleteLatestTagException()
        }
        val callLogCount = llmCallLogRepository.countByPromptRefId(refId)
        if (callLogCount > 0) {
            throw RefHasCallLogsException()
        }
        additionalInputItemRepository.deleteByPromptRefId(refId)
        promptRefRepository.delete(ref)
    }

    @Transactional
    fun updateRef(
        workspaceId: UUID,
        promptId: UUID,
        refId: Long,
        name: String?,
        description: String?,
        instructions: String?,
    ): PromptRef {
        val ref = requireRef(workspaceId, promptId, refId)
        if (ref.type == PromptRefType.TAG) {
            throw TagImmutableException()
        }
        if (!name.isNullOrBlank()) {
            ref.name = name
        }
        if (description != null) {
            ref.description = description
        }
        if (instructions != null) {
            ref.instructions = instructions
        }
        return promptRefRepository.save(ref)
    }

    @Transactional
    fun createRef(
        workspaceId: UUID,
        promptId: UUID,
        name: String,
        type: PromptRefType,
        fromRefId: Long?,
        description: String?,
    ): PromptRef {
        val prompt = requirePrompt(workspaceId, promptId)
        val refCount = promptRefRepository.countByPromptId(promptId)
        if (refCount >= 100) {
            throw PromptRefLimitExceededException()
        }
        val sourceRef = fromRefId?.let { requireRefOf(prompt, it) }
        if (sourceRef == null && type == PromptRefType.TAG) {
            throw TagRequiresFromRefException()
        }

        val created = promptRefRepository.save(
            PromptRef(
                prompt = prompt,
                name = name,
                type = type,
                instructions = sourceRef?.instructions ?: "",
                description = description,
                sourceTagRef = sourceRef?.takeIf { it.type == PromptRefType.TAG },
            )
        )
        if (sourceRef != null) {
            val sourceInputs = additionalInputItemRepository.findByPromptRefIdOrderByPositionAsc(sourceRef.id)
            val copiedInputs = sourceInputs.mapIndexed { index, input ->
                AdditionalInputItem(
                    position = index + 1,
                    role = input.role,
                    promptRef = created,
                    message = input.message,
                )
            }
            additionalInputItemRepository.saveAll(copiedInputs)
        }
        if (type == PromptRefType.TAG) {
            // Policy: latestTag always points to the most recently created TAG.
            prompt.latestTag = created
            promptRepository.save(prompt)
        }
        return created
    }

    // ---- additional input ----

    @Transactional
    fun addAdditionalInput(
        workspaceId: UUID,
        promptId: UUID,
        refId: Long,
        position: Int?,
        role: InputRole,
        message: String,
    ): AdditionalInputItem {
        val ref = requireRef(workspaceId, promptId, refId)
        val count = additionalInputItemRepository.countByPromptRefId(refId)
        if (count >= 100) {
            throw AdditionalInputLimitExceededException()
        }
        val items = additionalInputItemRepository.findByPromptRefIdOrderByPositionAsc(refId).toMutableList()
        val insertAt = position?.coerceIn(1, items.size + 1) ?: (items.size + 1)
        val newItem = AdditionalInputItem(
            position = 0,
            role = role,
            promptRef = ref,
            message = message,
        )
        items.add(insertAt - 1, newItem)
        resequenceAdditionalInputs(items)
        return newItem
    }

    @Transactional(readOnly = true)
    fun listAdditionalInputs(
        workspaceId: UUID,
        promptId: UUID,
        refId: Long,
        pageable: Pageable,
    ): Page<AdditionalInputItem> {
        requireRef(workspaceId, promptId, refId)
        return additionalInputItemRepository.findByPromptRefId(refId, pageable)
    }

    @Transactional
    fun updateAdditionalInput(
        workspaceId: UUID,
        promptId: UUID,
        refId: Long,
        additionalInputItemId: Long,
        position: Int?,
        role: InputRole?,
        message: String?,
    ): AdditionalInputItem {
        requireRef(workspaceId, promptId, refId)
        val item = requireAdditionalInput(refId, additionalInputItemId)
        if (position != null) {
            item.position = position
        }
        if (role != null) {
            item.role = role
        }
        if (message != null) {
            item.message = message
        }
        if (position == null) {
            return additionalInputItemRepository.save(item)
        }

        val items = additionalInputItemRepository.findByPromptRefIdOrderByPositionAsc(refId).toMutableList()
        items.removeIf { it.id == item.id }
        val insertAt = position.coerceIn(1, items.size + 1)
        items.add(insertAt - 1, item)
        resequenceAdditionalInputs(items)
        return item
    }

    @Transactional
    fun deleteAdditionalInput(workspaceId: UUID, promptId: UUID, refId: Long, additionalInputItemId: Long) {
        requireRef(workspaceId, promptId, refId)
        additionalInputItemRepository.delete(requireAdditionalInput(refId, additionalInputItemId))
    }

    // ---- 조회 헬퍼 (IDOR) ----

    private fun requirePrompt(workspaceId: UUID, promptId: UUID): Prompt =
        promptRepository.findByIdAndWorkspaceId(promptId, workspaceId) ?: throw PromptNotFoundException(promptId)

    private fun requireRefOf(prompt: Prompt, refId: Long): PromptRef =
        promptRefRepository.findByIdAndPromptId(refId, requireNotNull(prompt.id)) ?: throw PromptRefNotFoundException(refId)

    private fun requireRef(workspaceId: UUID, promptId: UUID, refId: Long): PromptRef =
        requireRefOf(requirePrompt(workspaceId, promptId), refId)

    private fun requireAdditionalInput(refId: Long, additionalInputItemId: Long): AdditionalInputItem =
        additionalInputItemRepository.findByIdAndPromptRefId(additionalInputItemId, refId)
            ?: throw AdditionalInputNotFoundException(additionalInputItemId)

    private fun resequenceAdditionalInputs(items: List<AdditionalInputItem>) {
        items.forEachIndexed { index, input ->
            input.position = index + 1
        }
        additionalInputItemRepository.saveAll(items)
    }
}

data class AdditionalInputItemSpec(
    val position: Int?,
    val role: InputRole,
    val message: String,
)
