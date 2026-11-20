# CLAUDE.md

이 파일은 이 저장소에서 작업하는 Claude Code(claude.ai/code)를 위한 안내입니다.

## 프로젝트

PromptBox는 LLM 프롬프트를 테스트하고 관리하는 Spring Boot 4 / Kotlin 2.2 (JDK 21) 백엔드입니다. 여러 벤더(OpenAI, Google, xAI)를 아우르는 스트리밍 플레이그라운드, 이름과 버전이 있는 프롬프트, LLM 호출 로그, 데이터와 기본 옵션의 범위를 정하는 워크스페이스를 제공합니다. 코드 주석과 커밋 메시지는 한글로 작성합니다. 프론트엔드는 별도 저장소에 있습니다(CORS 허용: `http://localhost:3030`).

## 빌드와 테스트

Gradle 멀티 프로젝트 빌드입니다. 루트 앱 `prompt-box`와 라이브러리 서브프로젝트 `llm-stream-client`로 구성됩니다.

```bash
./gradlew build -x test                        # 빌드 (Dockerfile 도 이 명령 사용, jar 는 build/libs/prompt-box.jar)
./gradlew test                                 # 전체 테스트
./gradlew :llm-stream-client:test              # 라이브러리 테스트만 (Docker/DB 불필요)
./gradlew test --tests "malibu.llm.prompt.ExecuteStreamingSuccessTest"          # 클래스 하나
./gradlew test --tests "malibu.llm.prompt.ExecuteStreamingSuccessTest.someName" # 메서드 하나
./gradlew bootRun                              # 7070 포트로 실행
```

- 의존성 해석에 GitHub Packages 인증 정보가 필요합니다(`malibu.tracer:tracer-webmvc`는 `maven.pkg.github.com/adrenalinee/tracer`에서 받습니다). Gradle 속성 `gpr.user`/`gpr.key` 또는 환경 변수 `USERNAME`/`TOKEN`으로 넘깁니다.
- 루트 앱 테스트는 Docker가 필요합니다. `TestcontainersConfig` 또는 `src/test/resources/application.properties`의 `jdbc:tc:` URL을 통해 Testcontainers Postgres 17을 사용합니다. 테스트는 `ddl-auto=create-drop`, Liquibase 비활성 상태로 실행됩니다.
- 테스트 생성자 주입이 켜져 있으므로(`spring.test.constructor.autowire=all`) 테스트 클래스는 빈을 생성자 파라미터로 받습니다.
- 기본 프로필은 파일 기반 H2 데이터베이스(`./h2data/`, PostgreSQL 모드)로 실행됩니다. Liquibase는 꺼져 있고 Hibernate `ddl-auto=create`가 시작할 때마다 엔티티로부터 스키마를 새로 만들기 때문에, 로컬 H2 데이터는 재시작하면 남지 않습니다. `postgres` 프로필은 `localhost:5432/prompt`를 사용합니다. `dev` 프로필은 `POSTGRESQL_URL`/`POSTGRESQL_USER`/`POSTGRESQL_PASSWORD`를 읽습니다. 두 Postgres 프로필 모두 Liquibase를 켭니다.

## 아키텍처

### `llm-stream-client` (라이브러리, `malibu.llm.streamclient`)
벤더 중립적인 스트리밍 추상화이며, GitHub Packages에 별도로 배포됩니다(`version`은 이 서브프로젝트의 `build.gradle.kts`에 있습니다).
- `LlmStreamClient.stream(LlmStreamRequest): Flux<LlmStreamEvent>`. `LlmStreamEvent`는 OpenAI Responses API 이벤트 수명주기(ResponseCreated → item/part Added → Delta → Done → ResponseCompleted, 그리고 reasoning·function-call 이벤트)를 본뜬 sealed 계층입니다. 모든 벤더 클라이언트는 자기 네이티브 스트림을 이 이벤트로 변환합니다.
- `LlmStreamClientProvider`는 **API key id**마다 클라이언트를 하나씩 캐시하고, 벤더별 생성 람다로 클라이언트를 만듭니다. 생성 람다는 `LlmStreamClientConfiguration`에 벤더 이름 `openai`/`google`/`xai`(`LlmStreamClients`)로 등록되며, 이 이름은 DB의 `LlmVendor.name`과 일치해야 합니다.
- 벤더 구현:
  - `openai/OpenaiLlmStreamClient`는 공식 `openai-java` SDK를 사용합니다.
  - `google/GoogleLlmStreamClient3`가 현재 쓰이는 Google 클라이언트이며 WebClient로 Interactions API를 호출합니다. `GoogleLlmStreamClient`(generateContent)는 이전 구현으로, 등록 코드가 주석 처리되어 있습니다.
  - `xai/XaiLlmStreamClient`는 WebClient로 Responses API를 호출합니다.
- 도구 호출: `ToolCallback`/`ToolDefinition`과 `schema/ToolSchemaGenerator`가 JSON 스키마를 만들고, `OpenAiToolSchemaNormalizer`가 이를 strict 모드에 맞게 정규화합니다. 클라이언트는 여러 라운드의 도구 루프를 내부에서 돌립니다. `StreamSession`은 모든 라운드를 소비자에게 하나의 응답처럼 보이게 합니다. ResponseCreated는 한 번, 누적 사용량을 담은 ResponseCompleted도 마지막에 한 번만 나가고, item index와 sequence는 라운드를 넘어 계속 증가합니다.
- `LlmStreamCancelRegistry`는 call-log id를 취소 sink에 연결합니다. 테스트용으로 `FakeLlmStreamClient`가 있습니다.

### 루트 앱 (`malibu.llm.prompt`)
- **실행 흐름:** `POST /workspaces/{id}/llm-calls`(`LlmCallsController`, SSE `text/event-stream`)는 `ExecuteStreamingOrchestratorImpl`로 넘어가며, 오케스트레이터는 다음을 수행합니다.
  1. 워크스페이스, 모델, API 키, 벤더가 서로 맞는지 검증합니다.
  2. `PromptRef`(프롬프트 버전) 또는 원문 텍스트에서 instructions를 결정하고, 추가 입력 항목을 붙입니다.
  3. 워크스페이스 `DefaultLlmCallOptions`와 요청의 오버라이드를 병합한 뒤 `LlmModelType`에 맞게 필터링합니다.
  4. API 키를 복호화하고(`ApiKeyCipher`, AES 키는 `app.security.api-key-encryption.key`) 프로바이더 클라이언트로 스트리밍합니다.

  오케스트레이터는 먼저 `LogCreated` 이벤트를 내보내고, 출력 item·part·토큰 사용량을 `LlmCallLogTxService`를 통해 `LlmCallLog`에 저장하며, `LlmCallCancelService`와 취소 레지스트리로 취소를 지원합니다.
- **계층:** `api/`는 REST 컨트롤러, `service/`는 트랜잭션 서비스, `data/entity` + `data/repo`는 JPA 엔티티와 Spring Data 리포지토리입니다. 워크스페이스 콘텐츠는 `/workspaces/{workspaceId}/...` 아래에, 플랫폼 전체 읽기 전용 화면은 `/admin/...` 아래에 있습니다. 도메인 예외는 `error/Exceptions.kt`에 있고 `ApiExceptionHandler`에서 HTTP 응답으로 매핑됩니다.
- **스키마:** Liquibase SQL changeset은 `src/main/resources/db/changelog/changes/NNN_*.sql`에 있고 `db.changelog-master.xml`에 나열됩니다. 스키마를 바꿀 때는 번호를 매긴 새 changeset을 추가합니다. changeset은 PostgreSQL 기준으로 작성되며 H2에서는 Liquibase가 꺼져 있고 Hibernate가 엔티티로부터 스키마를 만듭니다. 그러니 엔티티와 changeset을 항상 일치시켜야 합니다.
- **보안:** Google이 발급한 JWT를 검증하는 OAuth2 리소스 서버입니다. `UserJwtAuthenticationConverter`가 토큰을 `users` 행에 매핑합니다. Swagger(`/swagger-ui`, `/api-docs`)를 제외한 모든 경로는 인증이 필요합니다. 권한은 RBAC입니다(`docs/authz-spec.md`). 모든 엔드포인트에 `@platformAuthz` / `@orgAuthz` / `@wsAuthz`를 쓰는 `@PreAuthorize`가 붙어 있고, `EndpointAuthorizationMappingTest`가 이를 스펙의 매핑 표와 하나씩 대조하므로, 새 엔드포인트는 그 표에도 추가해야 합니다. `local` 프로필에서는 `LocalAuthConfig`가 HS256 `JwtDecoder`로 바꿔 끼우고(시크릿은 `application-local.properties`), 인증 없이 호출할 수 있는 `POST /dev/token`(`DevTokenController`)이 테스트 토큰을 발급합니다. DB 프로필과 조합해 씁니다. 예: H2라면 `--spring.profiles.active=local`.
- **Jackson:** 앱은 대부분 Jackson 3(`tools.jackson.*`)을 씁니다. OpenAI SDK와 Swagger가 요구하는 곳에서만 `com.fasterxml` import가 남아 있습니다.
- `EntityManagerExceptionTranslationConfig`(테스트에서 `app.exception-translation.em.override`로 켜짐)는 EntityManager를 감싸서 제약 조건 위반이 `DataIntegrityViolationException`으로 올라오게 합니다.

### 테스트 패턴
- 오케스트레이터 테스트(`ExecuteStreaming*Test`)는 `@SpringBootTest`에 `TestcontainersConfig` + `ExecuteStreamingTestConfig`를 씁니다. 후자는 `@Primary` `LlmStreamClientProvider`를 제공하며, 그 `OPENAI-TEST` 벤더는 `TestLlmStreamClient`를 반환합니다. 이벤트를 지정하려면 `nextFlux`를 설정합니다.
- `llm-stream-client`의 벤더 클라이언트 테스트는 OkHttp `MockWebServer`와 MockK를 씁니다.

### 소스가 아닌 디렉터리
최상위 `dev/`(langchain4j Java 소스)와 `main/`(openai-java Kotlin 소스)은 서드파티 코드의 참고용 사본입니다. 어떤 Gradle 소스셋에도 **포함되지 않으며** 컴파일되지 않습니다.
