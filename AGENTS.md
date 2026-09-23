# Repository Guidelines

## Project Snapshot
`prompt-box` is a Kotlin 2.2.21 / Spring Boot 4.0.3 / Java 21 backend for managing and testing LLM prompts. The product scope includes playground execution, model/vendor comparison, named prompts, call logs, workspaces, model catalogs, and encrypted LLM API keys.

This is a Gradle multi-project repository:

- Root project `prompt-box` (`ai.prompt-box:prompt`): Spring Boot web application under `src/main/kotlin/malibu/llm/prompt`.
- Subproject `:llm-stream-client` (`ai.mystix:llm-stream-client`, currently `3.7-SNAPSHOT`): publishable streaming LLM client library used by the root app through `implementation(project(":llm-stream-client"))`.

Top-level `dev/` and `main/` contain reference/source extracts and are not the primary application source sets unless a task explicitly points there.

## Project Structure
Root app code lives in `src/main/kotlin/malibu/llm/prompt`:

- `api`: Spring MVC controllers and API response helpers.
- `service`: business services and transactional helpers.
- `data/entity`: JPA entities. Prefer regular JPA classes, not Kotlin `data class` entities.
- `data/repo`: Spring Data repositories.
- `config` and `security`: cross-cutting configuration, JWT resource server setup, API key encryption, tracer integration, paging, and compatibility shims.
- `error`: application exception types.
- `ExecuteService.kt` and `ExecuteStreamingOrchestrator.kt`: main LLM execution and streaming orchestration path.

Database migrations live in `src/main/resources/db/changelog`, with individual SQL changes under `src/main/resources/db/changelog/changes`. Add schema changes through Liquibase changelogs instead of relying on `ddl-auto`.

The `:llm-stream-client` module lives under `llm-stream-client/src/main/kotlin/malibu/llm`:

- Common request/event/tool model: `LlmStreamModels.kt`, `LlmStreamEnums.kt`, `ToolDefinition(s).kt`, `LlmToolSpec.kt`, `ToolCallback.kt`.
- Vendor clients: `openai`, `google`, and `xai` packages.
- Schema helpers: `schema/ToolSchemaGenerator.kt` and `schema/OpenAiToolSchemaNormalizer.kt`.
- Backpressure and cancellation helpers: `LlmStreamBackpressure.kt`, `BufferedStepEventEmitter.kt`, `LlmStreamCancelRegistry.kt`, `StreamSession.kt`.

## Build, Test, and Run
Use the Gradle wrapper from the repository root:

- `./gradlew build`: builds all modules and runs the full test suite.
- `./gradlew test`: runs root and subproject tests.
- `./gradlew :llm-stream-client:test`: tests only the streaming client library.
- `./gradlew test --tests "mystix.prompt.SomeTest"`: runs a specific root test class.
- `./gradlew :llm-stream-client:test --tests "mystix.prompt.llm.SomeTest"`: runs a specific library test class.
- `./gradlew bootRun`: starts the Spring Boot app on port `7070`.
- `./gradlew clean`: removes build outputs.
- `./gradlew :llm-stream-client:publish`: publishes the library to GitHub Packages when credentials are configured.

Root app tests use Testcontainers with PostgreSQL, so Docker must be running for root `test` or full `build`. Most `:llm-stream-client` tests use MockWebServer/Reactor Test and do not require Docker.

The local app configuration expects PostgreSQL at `localhost:5432`, database `prompt`, user `prompt`; see `src/main/resources/application.properties`.

## LLM Streaming Architecture
The most important runtime path is:

1. A controller in `mystix.prompt.api` accepts an execution request.
2. `ExecuteService` and `ExecuteStreamingOrchestrator` resolve workspace, model, vendor, encrypted API key, prompt refs, default options, and overrides.
3. `ApiKeyCipher` decrypts the API key.
4. `LlmStreamClientProvider` creates or reuses a vendor client by API key id and vendor.
5. The selected client streams `Flux<LlmStreamEvent>`.
6. The orchestrator creates and updates `LlmCallLog`, `OutputItem`, and `OutputItemPart` records as stream events arrive.
7. Cancellation flows through `LlmStreamCancelRegistry` and `LlmCallCancelService`.

`LlmCallLogTxService` is the transactional boundary for streaming log writes. The streaming pipeline itself is Reactor code, so database writes should remain explicit and transaction-scoped.

## `:llm-stream-client` Guidelines
Keep the library vendor-agnostic at its public boundary. Shared callers should deal with `LlmStreamRequest`, `LlmStreamOptions`, `LlmToolSpec`, and `LlmStreamEvent`; only vendor packages should know native request/response formats.

Vendor implementation notes:

- OpenAI uses the official `openai-java` SDK.
- Google uses raw HTTP/WebClient transport for Gemini APIs.
- xAI uses raw HTTP/WebClient transport for Responses API.

When mapping common request options to vendor raw requests, set every common option that the vendor API actually supports. Do not silently patch, normalize, infer, or auto-fill invalid user input. If a required raw field cannot be mapped because the common request does not contain the needed value, fail fast with an explicit exception instead of inventing a fallback. If the user supplied a value or value combination that the vendor API spec may reject, pass it through in the raw request rather than rewriting, dropping, or special-casing it to avoid an API error. The goal is to make it clear whether a request succeeded because the user input was valid, not because a vendor implementation quietly corrected it. For example, an xAI request with `instructions` but no `input` should fail validation instead of auto-filling `input` from `instructions`.

Tool/function-call execution is handled inside the stream client. Preserve the abstraction: callers should receive common stream events and should not need to understand vendor-specific function-call loops.

Use existing exception types in `llm/exception` for invalid input, unsupported input types, unsupported tools, and tool argument parsing failures. Add focused tests whenever request mapping, stream parsing, tool execution, backpressure, or cancellation behavior changes.

## Coding Style
Follow existing Kotlin conventions:

- 4-space indentation.
- Uppercase enum constants, for example `SUCCESS`, `BRANCH`, `HIGH`.
- Package boundaries should match responsibilities.
- Prefer constructor injection and explicit service/repository/controller names such as `PromptService`, `PromptRepository`, `PromptsController`.
- Keep comments short and only where they clarify non-obvious behavior.
- Do not introduce broad abstractions unless they match existing local patterns or remove real complexity.

The root build enables strict Kotlin/JVM behavior through compiler options such as `-Xjsr305=strict` and `-Xannotation-default-target=param-property`. Be careful when changing build plugins or dependency management because subprojects inherit shared Kotlin/Spring setup.

## Testing Guidelines
Tests live in:

- `src/test/kotlin/mystix/prompt` for the Spring Boot app.
- `llm-stream-client/src/test/kotlin/mystix/prompt/llm` for the streaming client module.

JUnit 5 is the primary test framework. The project also uses Spring Boot test support, MockK, Reactor Test, MockWebServer, and Testcontainers. Test class names use `*Test` or `*Tests`.

For root app behavior, cover controller contracts, transactional service behavior, persistence constraints, streaming log finalization, cancellation, and error paths. For `:llm-stream-client`, cover vendor request serialization, stream event translation, tool invocation loops, usage parsing, and validation failures.

Run the narrowest useful test first, then broader tests when risk warrants it.

## Security and Configuration
Do not log or expose raw LLM API keys. API keys are encrypted at rest through `app.security.api-key-encryption.key`; development values in config files must not be reused in real environments.

JWT resource server configuration validates Google-issued tokens in `application.yml`. Keep local secrets out of version control, including Gradle GitHub Packages credentials (`gpr.user` / `gpr.key`) and environment secrets.

GitHub Packages are used both for consuming `malibu.tracer` and publishing `:llm-stream-client`; credentials come from Gradle properties or `USERNAME` / `TOKEN`.

## Commit and PR Notes
Recent commit messages are short, imperative, and often Korean, for example `llm stream client 를 서브 모듈로 분리 시키고 라이브러리화 할 준비`. Keep commits focused.

PRs should include:

- concise summary,
- API/schema impact,
- test coverage notes,
- example request/response payloads when endpoint or LLM request behavior changes.
