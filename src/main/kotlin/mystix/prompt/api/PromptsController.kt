package mystix.prompt.api

import mystix.prompt.data.PromptRefType
import mystix.prompt.data.entity.AdditionalInputItem
import mystix.prompt.data.entity.Prompt
import mystix.prompt.data.entity.PromptRef
import mystix.prompt.service.AdditionalInputItemSpec
import mystix.prompt.service.PromptService
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/prompts")
class PromptsController(
    private val promptService: PromptService,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: PromptCreateRequest): PromptResponse {
        val prompt = promptService.createPrompt(
            workspaceId = request.workspaceId,
            name = request.name,
            description = request.description,
            instructions = request.instructions,
            additionalInputs = request.additionalInputs.map { it.toSpec() },
            mainRefName = request.mainRefName,
        )
        return prompt.toResponse()
    }

    @GetMapping
    fun list(
        @RequestParam(required = false) workspaceId: UUID?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptResponse> =
        promptService.listPrompts(workspaceId, name, pageable).toResponse { it.toResponse() }

    @GetMapping("/{promptId}")
    fun get(@PathVariable promptId: UUID): PromptResponse {
        return promptService.getPrompt(promptId).toResponse()
    }

    @PatchMapping("/{promptId}")
    fun update(
        @PathVariable promptId: UUID,
        @RequestBody request: PromptUpdateRequest,
    ): PromptResponse {
        return promptService.updatePrompt(
            promptId,
            request.name,
            request.description,
            request.defaultBranchRefId,
            request.latestTagRefId,
        ).toResponse()
    }

    @DeleteMapping("/{promptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable promptId: UUID) {
        promptService.deletePrompt(promptId)
    }

    @PostMapping("/{promptId}/refs")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRef(
        @PathVariable promptId: UUID,
        @RequestBody request: PromptRefCreateRequest,
    ): PromptRefResponse {
        return promptService.createRef(
            promptId = promptId,
            name = request.name,
            type = request.type,
            fromRefId = request.fromRefId,
            description = request.description,
        ).toResponse()
    }

    @GetMapping("/{promptId}/refs")
    fun listRefs(
        @PathVariable promptId: UUID,
        @RequestParam(required = false) type: PromptRefType?,
        @RequestParam(required = false) name: String?,
        @PageableDefault(size = 20, sort = ["createdAt"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<PromptRefResponse> =
        promptService.listRefs(promptId, type, name, pageable).toResponse { it.toResponse() }

    @GetMapping("/{promptId}/refs/{refId}")
    fun getRef(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
    ): PromptRefResponse {
        return promptService.getRef(promptId, refId).toResponse()
    }

    @PatchMapping("/{promptId}/refs/{refId}")
    fun updateRef(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @RequestBody request: PromptRefUpdateRequest,
    ): PromptRefResponse {
        return promptService.updateRef(
            promptId = promptId,
            refId = refId,
            name = request.name,
            description = request.description,
            instructions = request.instructions,
        ).toResponse()
    }

    @DeleteMapping("/{promptId}/refs/{refId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRef(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
    ) {
        promptService.deleteRef(promptId, refId)
    }

    @PostMapping("/{promptId}/refs/{refId}/additional-input-items")
    @ResponseStatus(HttpStatus.CREATED)
    fun addAdditionalInput(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @RequestBody request: AdditionalInputCreateRequest,
    ): AdditionalInputItemResponse {
        return promptService.addAdditionalInput(
            promptId = promptId,
            refId = refId,
            position = request.position,
            role = request.role,
            message = request.message,
        ).toResponse()
    }

    @GetMapping("/{promptId}/refs/{refId}/additional-input-items")
    fun listAdditionalInputs(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PageableDefault(size = 20, sort = ["position"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<AdditionalInputItemResponse> =
        promptService.listAdditionalInputs(promptId, refId, pageable).toResponse { it.toResponse() }

    @PatchMapping("/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}")
    fun updateAdditionalInput(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PathVariable additionalInputItemId: Long,
        @RequestBody request: AdditionalInputUpdateRequest,
    ): AdditionalInputItemResponse {
        return promptService.updateAdditionalInput(
            promptId = promptId,
            refId = refId,
            additionalInputItemId = additionalInputItemId,
            position = request.position,
            role = request.role,
            message = request.message,
        ).toResponse()
    }

    @DeleteMapping("/{promptId}/refs/{refId}/additional-input-items/{additionalInputItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAdditionalInput(
        @PathVariable promptId: UUID,
        @PathVariable refId: Long,
        @PathVariable additionalInputItemId: Long,
    ) {
        promptService.deleteAdditionalInput(promptId, refId, additionalInputItemId)
    }
}

data class PromptCreateRequest(
    val workspaceId: UUID,
    val name: String,
    val description: String? = null,
    val instructions: String,
    val additionalInputs: List<AdditionalInputRequest> = emptyList(),
    val mainRefName: String = "main",
)

data class PromptUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val defaultBranchRefId: Long? = null,
    val latestTagRefId: Long? = null,
)

data class AdditionalInputRequest(
    val position: Int?,
    val role: mystix.prompt.data.InputRole,
    val message: String,
)

data class PromptRefCreateRequest(
    val name: String,
    val type: PromptRefType,
    val fromRefId: Long? = null,
    val description: String? = null,
)

data class PromptRefUpdateRequest(
    val name: String? = null,
    val description: String? = null,
    val instructions: String? = null,
)

data class PromptResponse(
    val id: UUID,
    val name: String,
    val description: String?,
    val workspaceId: UUID,
    val defaultBranchRefId: Long?,
    val latestTagRefId: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class PromptRefResponse(
    val id: Long,
    val promptId: UUID,
    val name: String,
    val type: PromptRefType,
    val instructions: String,
    val description: String?,
    val sourceTagRefId: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class AdditionalInputItemResponse(
    val id: Long,
    val position: Int,
    val role: mystix.prompt.data.InputRole,
    val message: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
)

data class AdditionalInputCreateRequest(
    val position: Int?,
    val role: mystix.prompt.data.InputRole,
    val message: String,
)

data class AdditionalInputUpdateRequest(
    val position: Int? = null,
    val role: mystix.prompt.data.InputRole? = null,
    val message: String? = null,
)

fun Prompt.toResponse() = PromptResponse(
    id = requireNotNull(id),
    name = name,
    description = description,
    workspaceId = requireNotNull(workspace.id),
    defaultBranchRefId = defaultBranch?.id,
    latestTagRefId = latestTag?.id,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun PromptRef.toResponse() = PromptRefResponse(
    id = id,
    promptId = requireNotNull(prompt.id),
    name = name,
    type = type,
    instructions = instructions,
    description = description,
    sourceTagRefId = sourceTagRef?.id,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AdditionalInputItem.toResponse() = AdditionalInputItemResponse(
    id = id,
    position = position,
    role = role,
    message = message,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AdditionalInputRequest.toSpec() = AdditionalInputItemSpec(
    position = position,
    role = role,
    message = message,
)
