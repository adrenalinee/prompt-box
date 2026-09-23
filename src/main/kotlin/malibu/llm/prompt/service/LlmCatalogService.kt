package malibu.llm.prompt.service

import malibu.llm.prompt.data.LlmModelType
import malibu.llm.prompt.data.entity.LlmApiKey
import malibu.llm.prompt.data.entity.LlmModel
import malibu.llm.prompt.data.entity.LlmVendor
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.ApiKeyNotFoundException
import malibu.llm.prompt.error.ModelNotFoundException
import malibu.llm.prompt.error.VendorHasDependenciesException
import malibu.llm.prompt.error.VendorNotFoundException
import malibu.llm.prompt.error.WorkspaceNotFoundException
import malibu.llm.prompt.security.ApiKeyCipher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LlmCatalogService(
    private val llmVendorRepository: LlmVendorRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
    private val apiKeyCipher: ApiKeyCipher,
) {

    @Transactional(readOnly = true)
    fun listVendors(name: String?, pageable: Pageable): Page<LlmVendor> {
        if (name.isNullOrBlank()) {
            return llmVendorRepository.findAll(pageable)
        }
        return llmVendorRepository.findByNameContainingIgnoreCase(name, pageable)
    }

    @Transactional(readOnly = true)
    fun getVendor(vendorId: UUID): LlmVendor {
        return llmVendorRepository.findById(vendorId)
            .orElseThrow { VendorNotFoundException(vendorId) }
    }

    @Transactional
    fun createVendor(name: String, description: String?): LlmVendor {
        return llmVendorRepository.save(
            LlmVendor(
                name = name,
                description = description,
            )
        )
    }

    @Transactional
    fun updateVendor(
        vendorId: UUID,
        name: String?,
        description: String?,
    ): LlmVendor {
        val vendor = llmVendorRepository.findById(vendorId)
            .orElseThrow { VendorNotFoundException(vendorId) }
        if (!name.isNullOrBlank()) {
            vendor.name = name
        }
        if (description != null) {
            vendor.description = description
        }
        return llmVendorRepository.save(vendor)
    }

    @Transactional
    fun deleteVendor(vendorId: UUID) {
        val vendor = llmVendorRepository.findById(vendorId)
            .orElseThrow { VendorNotFoundException(vendorId) }
        val modelCount = llmModelRepository.countByLlmVendorId(vendorId)
        val apiKeyCount = llmApiKeyRepository.countByLlmVendorId(vendorId)
        if (modelCount > 0 || apiKeyCount > 0) {
            throw VendorHasDependenciesException()
        }
        llmVendorRepository.delete(vendor)
    }

    @Transactional
    fun createModel(
        vendorId: UUID,
        name: String,
        modelKey: String,
        modelType: LlmModelType,
        contextWindow: Long? = null,
        inputPrice: Long? = null,
        outputPrice: Long? = null,
        description: String? = null,
    ): LlmModel {
        val vendor = llmVendorRepository.findById(vendorId)
            .orElseThrow { VendorNotFoundException(vendorId) }

        return llmModelRepository.save(
            LlmModel(
                name = name,
                modelKey = modelKey,
                modelType = modelType,
                llmVendor = vendor,
                contextWindow = contextWindow,
                inputPrice = inputPrice,
                outputPrice = outputPrice,
                description = description,
            )
        )
    }

    @Transactional(readOnly = true)
    fun listModels(
        vendorId: UUID?,
        name: String?,
        pageable: Pageable,
    ): Page<LlmModel> {
        if (vendorId != null) {
            llmVendorRepository.findById(vendorId)
                .orElseThrow { VendorNotFoundException(vendorId) }
        }
        if (vendorId != null && name.isNullOrBlank().not()) {
            return llmModelRepository.findByLlmVendorIdAndNameContainingIgnoreCase(vendorId, name!!, pageable)
        }
        if (vendorId != null) {
            return llmModelRepository.findByLlmVendorId(vendorId, pageable)
        }
        if (name.isNullOrBlank().not()) {
            return llmModelRepository.findByNameContainingIgnoreCase(name!!, pageable)
        }
        return llmModelRepository.findAll(pageable)
    }

    @Transactional(readOnly = true)
    fun listModelsForWorkspace(
        workspaceId: UUID,
        vendorId: UUID?,
        name: String?,
        pageable: Pageable,
    ): Page<LlmModel> {
        workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        return listModels(vendorId, name, pageable)
    }

    @Transactional(readOnly = true)
    fun getModel(modelId: Long): LlmModel {
        return llmModelRepository.findById(modelId)
            .orElseThrow { ModelNotFoundException(modelId) }
    }

    @Transactional
    fun updateModel(
        modelId: Long,
        vendorId: UUID?,
        name: String?,
        modelKey: String?,
        modelType: LlmModelType?,
        contextWindow: Long?,
        inputPrice: Long?,
        outputPrice: Long?,
        description: String?,
    ): LlmModel {
        val model = llmModelRepository.findById(modelId)
            .orElseThrow { ModelNotFoundException(modelId) }
        if (vendorId != null) {
            val vendor = llmVendorRepository.findById(vendorId)
                .orElseThrow { VendorNotFoundException(vendorId) }
            model.llmVendor = vendor
        }
        if (!name.isNullOrBlank()) {
            model.name = name
        }
        if (!modelKey.isNullOrBlank()) {
            model.modelKey = modelKey
        }
        if (modelType != null) {
            model.modelType = modelType
        }
        if (contextWindow != null) {
            model.contextWindow = contextWindow
        }
        if (inputPrice != null) {
            model.inputPrice = inputPrice
        }
        if (outputPrice != null) {
            model.outputPrice = outputPrice
        }
        if (description != null) {
            model.description = description
        }
        return llmModelRepository.save(model)
    }

    @Transactional
    fun deleteModel(modelId: Long) {
        val model = llmModelRepository.findById(modelId)
            .orElseThrow { ModelNotFoundException(modelId) }
        llmModelRepository.delete(model)
    }

    @Transactional
    fun createApiKey(
        workspaceId: UUID,
        vendorId: UUID,
        name: String,
        value: String,
        description: String?,
    ): LlmApiKey {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        val vendor = llmVendorRepository.findById(vendorId)
            .orElseThrow { VendorNotFoundException(vendorId) }

        val encryptedValue = apiKeyCipher.encrypt(value)
        val last5 = value.takeLast(5)
        return llmApiKeyRepository.save(
            LlmApiKey(
                value = encryptedValue,
                last5 = last5,
                llmVendor = vendor,
                name = name,
                workspace = workspace,
                description = description,
            )
        )
    }

    @Transactional(readOnly = true)
    fun listApiKeys(
        workspaceId: UUID?,
        vendorId: UUID?,
        name: String?,
        pageable: Pageable,
    ): Page<LlmApiKey> {
        if (workspaceId != null) {
            workspaceRepository.findById(workspaceId)
                .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        }
        if (vendorId != null) {
            llmVendorRepository.findById(vendorId)
                .orElseThrow { VendorNotFoundException(vendorId) }
        }

        return when {
            workspaceId != null && vendorId != null && name.isNullOrBlank().not() ->
                llmApiKeyRepository.findByWorkspaceIdAndLlmVendorIdAndNameContainingIgnoreCase(
                    workspaceId,
                    vendorId,
                    name!!,
                    pageable,
                )
            workspaceId != null && vendorId != null ->
                llmApiKeyRepository.findByWorkspaceIdAndLlmVendorId(workspaceId, vendorId, pageable)
            workspaceId != null && name.isNullOrBlank().not() ->
                llmApiKeyRepository.findByWorkspaceIdAndNameContainingIgnoreCase(workspaceId, name!!, pageable)
            workspaceId != null ->
                llmApiKeyRepository.findByWorkspaceId(workspaceId, pageable)
            vendorId != null && name.isNullOrBlank().not() ->
                llmApiKeyRepository.findByLlmVendorIdAndNameContainingIgnoreCase(vendorId, name!!, pageable)
            vendorId != null ->
                llmApiKeyRepository.findByLlmVendorId(vendorId, pageable)
            name.isNullOrBlank().not() ->
                llmApiKeyRepository.findByNameContainingIgnoreCase(name!!, pageable)
            else ->
                llmApiKeyRepository.findAll(pageable)
        }
    }

    @Transactional(readOnly = true)
    fun getApiKey(apiKeyId: UUID): LlmApiKey {
        return llmApiKeyRepository.findById(apiKeyId)
            .orElseThrow { ApiKeyNotFoundException(apiKeyId) }
    }

    @Transactional
    fun updateApiKey(
        apiKeyId: UUID,
        workspaceId: UUID?,
        vendorId: UUID?,
        name: String?,
        value: String?,
        description: String?,
    ): LlmApiKey {
        val apiKey = llmApiKeyRepository.findById(apiKeyId)
            .orElseThrow { ApiKeyNotFoundException(apiKeyId) }
        if (workspaceId != null) {
            val workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow { WorkspaceNotFoundException(workspaceId) }
            apiKey.workspace = workspace
        }
        if (vendorId != null) {
            val vendor = llmVendorRepository.findById(vendorId)
                .orElseThrow { VendorNotFoundException(vendorId) }
            apiKey.llmVendor = vendor
        }
        if (!name.isNullOrBlank()) {
            apiKey.name = name
        }
        if (!value.isNullOrBlank()) {
            apiKey.value = apiKeyCipher.encrypt(value)
            apiKey.last5 = value.takeLast(5)
        }
        if (description != null) {
            apiKey.description = description
        }
        return llmApiKeyRepository.save(apiKey)
    }

    @Transactional
    fun deleteApiKey(apiKeyId: UUID) {
        val apiKey = llmApiKeyRepository.findById(apiKeyId)
            .orElseThrow { ApiKeyNotFoundException(apiKeyId) }
        llmApiKeyRepository.delete(apiKey)
    }
}
