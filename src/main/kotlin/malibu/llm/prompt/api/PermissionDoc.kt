package malibu.llm.prompt.api

/**
 * API 문서의 "필요 권한" 문장을 직접 쓴다. (기본은 @PreAuthorize 에서 자동으로 만든다. PermissionDocs 참고)
 *
 * @PreAuthorize 가 조건식이거나, 메서드 안에서 권한을 더 검사해 자동 문장이 부족할 때만 쓴다.
 * 문장에는 @PreAuthorize 의 권한 코드가 모두 들어 있어야 한다(PermissionDocsTest 가 검사한다).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PermissionDoc(val value: String)
