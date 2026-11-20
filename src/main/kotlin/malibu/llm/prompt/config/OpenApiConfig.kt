package malibu.llm.prompt.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 문서(`/api-docs`, `/swagger-ui`)의 API 전체 소개.
 * 컨트롤러별 설명은 각 컨트롤러의 @Tag / @Operation 에 있다.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun promptBoxOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("PromptBox API")
            .version("v1")
            .description(DESCRIPTION.trimIndent()),
    )

    /** 태그(컨트롤러 묶음)를 화면에서 읽는 순서로 정렬한다. 목록에 없는 태그는 뒤에 붙는다. */
    @Bean
    fun tagOrderCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.tags = openApi.tags?.sortedBy { tag ->
            TAG_ORDER.indexOf(tag.name).let { if (it < 0) TAG_ORDER.size else it }
        }
    }

    private companion object {
        val TAG_ORDER = listOf(
            "상태", "내 정보", "권한 카탈로그",
            "Organization", "Workspace", "Prompt",
            "LLM 호출", "LLM API 키", "LLM 벤더", "LLM 모델",
            "관리: 사용자", "관리: Organization", "관리: 콘텐츠 조회",
            "개발용 (local)",
        )

        const val DESCRIPTION = """
            LLM 프롬프트를 테스트하고 관리하는 PromptBox 의 백엔드 API.

            ## 리소스 구조
            - **Organization**: workspace 를 소유하는 최상위 단위. 멤버와 org 역할(ORG_OWNER / ORG_ADMIN / ORG_MEMBER)을 가진다.
            - **Workspace**: 프롬프트, API 키, LLM 호출 로그, 기본 호출 옵션의 범위. 대부분의 API 는 `/workspaces/{workspaceId}/...` 아래에 있다.
            - **Prompt / Ref**: 프롬프트는 git 처럼 ref 로 버전을 관리한다. `BRANCH` 는 수정할 수 있고, `TAG` 는 고정된 스냅샷(publish)이다.
              프롬프트에는 항상 default branch 가 있다. latest tag 는 TAG 를 만들 때마다 그 TAG 로 바뀌며, 직접 바꾸거나 해제할 수도 있다.
            - **LLM 카탈로그**: 벤더(openai / google / xai)와 모델은 플랫폼 전체가 공유한다. API 키는 workspace 에 속한다.
            - **LLM 호출**: 프롬프트 ref 또는 원문 instructions 로 모델을 스트리밍(SSE) 호출하고, 결과는 호출 로그로 남는다.

            ## 인증
            `/`, 이 문서, local 의 `POST /dev/token` 을 뺀 모든 API 는 `Authorization: Bearer <JWT>` 가 필요하다.
            서버는 Google 이 발급한 ID 토큰을 받으며, 관리자가 사전 등록한 사용자만 쓸 수 있다.
            local 프로필에서는 `POST /dev/token` 으로 테스트 토큰을 받는다.

            ## 권한
            역할 기반(RBAC). scope 는 PLATFORM → ORGANIZATION → WORKSPACE 3단계이며, org 역할은 그 org 의 모든 workspace 역할을 함의한다.
            - 접근할 수 없는(멤버가 아닌) org / workspace 의 리소스는 존재 여부를 숨기기 위해 **404**.
            - 멤버지만 권한이 부족하면 **403** `FORBIDDEN`.
            - 화면에서 버튼 노출을 정할 때는 `GET /workspaces/{id}/my-permissions`, `GET /organizations/{id}/my-permissions` 를 쓴다.

            ## PATCH 규칙
            PATCH 는 보낸 필드만 바꾼다. 필드마다 `null` 의 의미가 다르며 각 필드 설명에 적혀 있다.
            - **null 이면 지운다**: 비울 수 있는 필드(description 등). 키를 아예 보내지 않으면 그대로 둔다.
            - **null 이면 변경 없음**: 필수 필드(name 등). 보내지 않은 것과 같다.

            ## 오류 응답
            오류는 `ApiErrorResponse` 형식(`status`, `error`, `code`, `message`, `path`)이다. 클라이언트는 `code` 로 분기한다.
            예: 401(토큰 없음/무효), 403 `USER_NOT_REGISTERED` / `USER_DISABLED` / `FORBIDDEN`, 404, 409(다른 리소스가 참조 중).

            ## 목록 API
            목록은 `page`(0부터), `size`(최대 100), `sort`(예: `createdAt,desc`) 쿼리로 페이지를 나눈다.
        """
    }
}
