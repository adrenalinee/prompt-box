package malibu.llm.prompt.error

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

/** 403. code 는 응답 body 의 code 로 내려간다. */
open class ForbiddenException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 권한 판정 결과 NOT_MEMBER. 리소스 존재 여부를 노출하지 않도록 일반적인 404 메시지만 내려간다.
 */
class AuthzNotFoundException : NotFoundException(
    message = "리소스를 찾을 수 없습니다.",
)

class UserDisabledException : ForbiddenException(
    code = "USER_DISABLED",
    message = "비활성화된 사용자입니다.",
)

class EmailAlreadyRegisteredException(email: String) : ConflictException(
    message = "이미 등록된 이메일입니다. email: $email",
)

class WorkspaceNotFoundException(workspaceId: UUID) : NotFoundException(
    message = "워크스페이스를 찾을 수 없습니다. workspaceId: $workspaceId",
)

class OrganizationNotFoundException(organizationId: UUID) : NotFoundException(
    message = "조직을 찾을 수 없습니다. organizationId: $organizationId",
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

class UserNotFoundException(userId: UUID) : NotFoundException(
    message = "사용자를 찾을 수 없습니다. userId: $userId",
)

class UserEmailNotFoundException(email: String) : NotFoundException(
    message = "등록된 사용자가 없습니다. email: $email",
)

class MemberNotFoundException : NotFoundException(
    message = "멤버를 찾을 수 없습니다.",
)

class MemberAlreadyExistsException : ConflictException(
    message = "이미 멤버입니다.",
)

/** workspace 멤버는 먼저 그 org 의 멤버여야 한다. */
class NotOrganizationMemberException : BadRequestException(
    message = "조직 멤버가 아닌 사용자는 workspace 멤버로 추가할 수 없습니다.",
)

class InvalidRoleException(roleCode: String, expected: String) : BadRequestException(
    message = "사용할 수 없는 역할입니다. role: $roleCode, 가능한 역할: $expected",
)

/** 역할 상한: organization.member:manage 로는 ORG_OWNER 를 부여/회수할 수 없다. (authz-spec §7) */
class OwnerRoleChangeNotAllowedException : ForbiddenException(
    code = "OWNER_CHANGE_NOT_ALLOWED",
    message = "ORG_OWNER 는 멤버 관리로 부여/회수할 수 없습니다. 소유권 이전을 사용하세요.",
)

class SelfLockoutNotAllowedException : BadRequestException(
    message = "자기 자신을 비활성화하거나 자신의 플랫폼 역할을 회수할 수 없습니다.",
)

class OrganizationHasDependenciesException : ConflictException(
    message = "organization에 workspace가 있습니다. workspace를 먼저 삭제하세요.",
)
