package mystix.prompt.error

import java.util.UUID

open class BadRequestException(
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

open class NotFoundException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

open class ConflictException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class WorkspaceNotFoundException(workspaceId: UUID) : NotFoundException(
    message = "워크스페이스를 찾을 수 없습니다. workspaceId: $workspaceId",
)

class VendorNotFoundException(vendorId: UUID) : NotFoundException(
    message = "벤더를 찾을 수 없습니다. vendorId: $vendorId",
)

class ModelNotFoundException(modelId: Long) : NotFoundException(
    message = "모델을 찾을 수 없습니다. modelId: $modelId",
)

class ApiKeyNotFoundException(apiKeyId: UUID) : NotFoundException(
    message = "API 키를 찾을 수 없습니다. apiKeyId: $apiKeyId",
)

class PromptNotFoundException(promptId: UUID) : NotFoundException(
    message = "프롬프트를 찾을 수 없습니다. promptId: $promptId",
)

class PromptRefNotFoundException(refId: Long) : NotFoundException(
    message = "프롬프트 ref를 찾을 수 없습니다. refId: $refId",
)

class AdditionalInputNotFoundException(additionalInputId: Long) : NotFoundException(
    message = "추가 입력을 찾을 수 없습니다. additionalInputItemId: $additionalInputId",
)

class CallLogNotFoundException(callLogId: UUID) : NotFoundException(
    message = "LLM 호출 로그를 찾을 수 없습니다. callLogId: $callLogId",
)

class VendorMismatchException : BadRequestException(
    message = "모델 벤더와 API 키 벤더가 일치하지 않습니다.",
)

class WorkspaceMismatchException : BadRequestException(
    message = "워크스페이스가 일치하지 않습니다.",
)

class InstructionsRequiredException : BadRequestException(
    message = "instructions 값이 필요합니다.",
)

class UnsupportedVendorException(vendorName: String) : BadRequestException(
    message = "지원하지 않는 벤더입니다. vendorName: $vendorName",
)

class GoogleInstructionsOnlyNotSupportedException : BadRequestException(
    message = "google 벤더는 instructions 단독 요청을 지원하지 않습니다. USER/ASSISTANT message input이 필요합니다.",
)

class GoogleSystemOrDeveloperInputNotSupportedException : BadRequestException(
    message = "google 벤더는 SYSTEM/DEVELOPER message input을 지원하지 않습니다.",
)

class XaiInstructionsOnlyNotSupportedException : BadRequestException(
    message = "xai 벤더는 instructions 단독 요청을 지원하지 않습니다. USER/ASSISTANT message input이 필요합니다.",
)

class RefNotInPromptException : BadRequestException(
    message = "ref가 프롬프트에 속하지 않습니다.",
)

class DefaultBranchMustBeBranchException : BadRequestException(
    message = "default branch는 BRANCH 타입이어야 합니다.",
)

class LatestTagMustBeTagException : BadRequestException(
    message = "latest tag는 TAG 타입이어야 합니다.",
)

class TagImmutableException : BadRequestException(
    message = "TAG ref는 변경할 수 없습니다.",
)

class PromptRefLimitExceededException : BadRequestException(
    message = "prompt ref 개수 제한을 초과했습니다.",
)

class TagRequiresFromRefException : BadRequestException(
    message = "TAG 생성에는 fromRefId가 필요합니다.",
)

class AdditionalInputLimitExceededException : BadRequestException(
    message = "additional input 개수 제한을 초과했습니다.",
)

class AdditionalInputNotInRefException : BadRequestException(
    message = "additional input이 ref에 속하지 않습니다.",
)

class ActiveStreamNotFoundException(callLogId: UUID) : BadRequestException(
    message = "활성 스트림을 찾을 수 없습니다. callLogId: $callLogId",
)

class WorkspaceHasDependenciesException : ConflictException(
    message = "workspace에 종속된 데이터가 있습니다.",
)

class VendorHasDependenciesException : ConflictException(
    message = "vendor에 종속된 데이터가 있습니다.",
)

class ModelHasDependenciesException : ConflictException(
    message = "model에 종속된 데이터가 있습니다.",
)

class ApiKeyHasDependenciesException : ConflictException(
    message = "api key에 종속된 데이터가 있습니다.",
)

class PromptHasCallLogsException : ConflictException(
    message = "prompt에 종속된 call log가 있습니다.",
)

class CannotDeleteDefaultBranchException : ConflictException(
    message = "default branch는 삭제할 수 없습니다.",
)

class CannotDeleteLatestTagException : ConflictException(
    message = "latest tag는 삭제할 수 없습니다.",
)

class RefHasCallLogsException : ConflictException(
    message = "ref에 종속된 call log가 있습니다.",
)
