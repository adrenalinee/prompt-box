package mystix.prompt.service

import mystix.prompt.data.PromptRefType
import mystix.prompt.data.entity.AdditionalInputItem
import mystix.prompt.data.entity.Prompt
import mystix.prompt.data.entity.PromptRef
import mystix.prompt.data.repo.AdditionalInputItemRepository
import mystix.prompt.data.repo.LlmCallLogRepository
import mystix.prompt.data.repo.PromptRefRepository
import mystix.prompt.data.repo.PromptRepository
import mystix.prompt.data.repo.WorkspaceRepository
import mystix.prompt.error.AdditionalInputLimitExceededException
import mystix.prompt.error.AdditionalInputNotFoundException
import mystix.prompt.error.AdditionalInputNotInRefException
import mystix.prompt.error.CannotDeleteDefaultBranchException
import mystix.prompt.error.CannotDeleteLatestTagException
import mystix.prompt.error.DefaultBranchMustBeBranchException
import mystix.prompt.error.LatestTagMustBeTagException
import mystix.prompt.error.PromptHasCallLogsException
import mystix.prompt.error.PromptNotFoundException
import mystix.prompt.error.PromptRefLimitExceededException
import mystix.prompt.error.PromptRefNotFoundException
import mystix.prompt.error.RefHasCallLogsException
import mystix.prompt.error.RefNotInPromptException
import mystix.prompt.error.TagImmutableException
import mystix.prompt.error.TagRequiresFromRefException
import mystix.prompt.error.WorkspaceNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PromptService(
    private val workspaceRepository: WorkspaceRepository,
    private val promptRepository: PromptRepository,
    private val promptRefRepository: PromptRefRepository,
    private val additionalInputItemRepository: AdditionalInputItemRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
) {

    @Transactional(readOnly = true)
    fun listPrompts(
        workspaceId: UUID?,
        name: String?,
        pageable: Pageable,
    ): Page<Prompt> {
        if (workspaceId != null) {
            workspaceRepository.findById(workspaceId)
                .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        }

        return when {
            workspaceId != null && name.isNullOrBlank().not() ->
                promptRepository.findByWorkspaceIdAndNameContainingIgnoreCase(workspaceId, name!!, pageable)
            workspaceId != null ->
                promptRepository.findByWorkspaceId(workspaceId, pageable)
            name.isNullOrBlank().not() ->
                promptRepository.findByNameContainingIgnoreCase(name!!, pageable)
            else ->
                promptRepository.findAll(pageable)
        }
    }

    @Transactional(readOnly = true)
    fun getPrompt(promptId: UUID): Prompt {
        return promptRepository.findById(promptId)
            .orElseThrow { PromptNotFoundException(promptId) }
    }

    @Transactional
    fun updatePrompt(
        promptId: UUID,
        name: String?,
        description: String?,
        defaultBranchRefId: Long?,
        latestTagRefId: Long?,
    ): Prompt {
        val prompt = promptRepository.findById(promptId)
            .orElseThrow { PromptNotFoundException(promptId) }
        if (!name.isNullOrBlank()) {
            prompt.name = name
        }
        if (description != null) {
            prompt.description = description
        }
        if (defaultBranchRefId != null) {
            val ref = promptRefRepository.findById(defaultBranchRefId)
                .orElseThrow { PromptRefNotFoundException(defaultBranchRefId) }
            if (requireNotNull(ref.prompt.id) != promptId) {
                throw RefNotInPromptException()
            }
            if (ref.type != PromptRefType.BRANCH) {
                throw DefaultBranchMustBeBranchException()
            }
            prompt.defaultBranch = ref
        }
        if (latestTagRefId != null) {
            val ref = promptRefRepository.findById(latestTagRefId)
                .orElseThrow { PromptRefNotFoundException(latestTagRefId) }
            if (requireNotNull(ref.prompt.id) != promptId) {
                throw RefNotInPromptException()
            }
            if (ref.type != PromptRefType.TAG) {
                throw LatestTagMustBeTagException()
            }
            prompt.latestTag = ref
        }
        return promptRepository.save(prompt)
    }

    @Transactional
    fun deletePrompt(promptId: UUID) {
        val prompt = promptRepository.findById(promptId)
            .orElseThrow { PromptNotFoundException(promptId) }
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

    @Transactional(readOnly = true)
    fun getRef(promptId: UUID, refId: Long): PromptRef {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
        return ref
    }

    @Transactional(readOnly = true)
    fun listRefs(
        promptId: UUID,
        type: PromptRefType?,
        name: String?,
        pageable: org.springframework.data.domain.Pageable,
    ): org.springframework.data.domain.Page<PromptRef> {
        promptRepository.findById(promptId)
            .orElseThrow { PromptNotFoundException(promptId) }

        return when {
            type != null && name.isNullOrBlank().not() ->
                promptRefRepository.findByPromptIdAndTypeAndNameContainingIgnoreCase(promptId, type, name!!, pageable)
            type != null ->
                promptRefRepository.findByPromptIdAndType(promptId, type, pageable)
            name.isNullOrBlank().not() ->
                promptRefRepository.findByPromptIdAndNameContainingIgnoreCase(promptId, name!!, pageable)
            else ->
                promptRefRepository.findByPromptId(promptId, pageable)
        }
    }

    @Transactional
    fun deleteRef(promptId: UUID, refId: Long) {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
        val prompt = promptRepository.findById(promptId)
            .orElseThrow { PromptNotFoundException(promptId) }
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
        promptId: UUID,
        refId: Long,
        name: String?,
        description: String?,
        instructions: String?,
    ): PromptRef {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
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
        promptId: UUID,
        name: String,
        type: PromptRefType,
        fromRefId: Long?,
        description: String?,
    ): PromptRef {
        val prompt = promptRepository.findById(promptId)
            .orElseThrow { PromptNotFoundException(promptId) }
        val refCount = promptRefRepository.countByPromptId(promptId)
        if (refCount >= 100) {
            throw PromptRefLimitExceededException()
        }
        val sourceRef = fromRefId?.let { refId ->
            val ref = promptRefRepository.findById(refId)
                .orElseThrow { PromptRefNotFoundException(refId) }
            if (requireNotNull(ref.prompt.id) != requireNotNull(prompt.id)) {
                throw RefNotInPromptException()
            }
            ref
        }
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

    @Transactional
    fun addAdditionalInput(
        promptId: UUID,
        refId: Long,
        position: Int?,
        role: mystix.prompt.data.InputRole,
        message: String,
    ): AdditionalInputItem {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
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
        promptId: UUID,
        refId: Long,
        pageable: Pageable,
    ): Page<AdditionalInputItem> {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
        return additionalInputItemRepository.findByPromptRefId(refId, pageable)
    }

    @Transactional
    fun updateAdditionalInput(
        promptId: UUID,
        refId: Long,
        additionalInputItemId: Long,
        position: Int?,
        role: mystix.prompt.data.InputRole?,
        message: String?,
    ): AdditionalInputItem {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
        val item = additionalInputItemRepository.findById(additionalInputItemId)
            .orElseThrow { AdditionalInputNotFoundException(additionalInputItemId) }
        if (requireNotNull(item.promptRef.id) != refId) {
            throw AdditionalInputNotInRefException()
        }
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
    fun deleteAdditionalInput(promptId: UUID, refId: Long, additionalInputItemId: Long) {
        val ref = promptRefRepository.findById(refId)
            .orElseThrow { PromptRefNotFoundException(refId) }
        if (requireNotNull(ref.prompt.id) != promptId) {
            throw RefNotInPromptException()
        }
        val item = additionalInputItemRepository.findById(additionalInputItemId)
            .orElseThrow { AdditionalInputNotFoundException(additionalInputItemId) }
        if (requireNotNull(item.promptRef.id) != refId) {
            throw AdditionalInputNotInRefException()
        }
        additionalInputItemRepository.delete(item)
    }

    private fun resequenceAdditionalInputs(items: List<AdditionalInputItem>) {
        items.forEachIndexed { index, input ->
            input.position = index + 1
        }
        additionalInputItemRepository.saveAll(items)
    }
}

data class AdditionalInputItemSpec(
    val position: Int?,
    val role: mystix.prompt.data.InputRole,
    val message: String,
)
