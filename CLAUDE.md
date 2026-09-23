# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`prompt-box` (root project name in `settings.gradle.kts`) is a Spring Boot 4 / Kotlin 2.2 / Java 21 backend for managing and testing LLM prompts (playground, compare, named prompts, call logs, workspaces). See `README.md` for product scope and `AGENTS.md` for additional repository guidelines.

The build is a Gradle multi-project:

- root (`ai.prompt-box:prompt`) — the Spring Boot web app under `src/main/kotlin/malibu/llm/prompt`.
- `:llm-stream-client` (`ai.mystix:llm-stream-client`, currently `version = "3.4"`) — a publishable library that hides per-vendor streaming differences behind a single `LlmStreamClient` interface. The root project depends on it via `implementation(project(":llm-stream-client"))`. It is also published to GitHub Packages (`mystix-ai/maven-artifacts`).

Two GitHub Packages repositories are used: `adrenalinee/tracer` (consumed at root) and `mystix-ai/maven-artifacts` (where `llm-stream-client` publishes). Both require `gpr.user`/`gpr.key` Gradle properties or `USERNAME`/`TOKEN` env vars.

## Common commands

Use the Gradle wrapper from the repo root:

- `./gradlew build` — build all modules and run tests. **Requires a running Docker daemon** (Testcontainers + PostgreSQL).
- `./gradlew test` — run tests. Same Docker requirement.
- `./gradlew :llm-stream-client:test` — test just the library module (no Docker needed).
- `./gradlew test --tests "mystix.prompt.SomeTest"` — run a single test class.
- `./gradlew bootRun` — start the Spring Boot app. Requires a local PostgreSQL at `localhost:5432`, db `prompt`, user `prompt` (see `src/main/resources/application.properties`).
- `./gradlew :llm-stream-client:publish` — publish the library to GitHub Packages (needs `gpr.user`/`gpr.key`).

The repo ships with a `Dockerfile` (multi-stage, Temurin 21, runs `./gradlew --no-daemon build -x test`).

## Architecture

### Streaming pipeline (the core of the system)

The end-to-end streaming flow is the most non-obvious part of the codebase. Reading `ExecuteStreamingOrchestrator` together with the `:llm-stream-client` module is the fastest way to get oriented.

1. HTTP request reaches a controller in `mystix.prompt.api` (e.g. `PromptsController`).
2. `ExecuteService` / `ExecuteStreamingOrchestrator` (`src/main/kotlin/malibu/llm/prompt`) resolves the workspace, model, API key, optional `PromptRef`, default + override options, and validates vendor-specific constraints (e.g. Google requires non-empty messages; system/developer roles aren't supported there).
3. The orchestrator decrypts the stored API key via `security.ApiKeyCipher` and obtains a vendor-specific `LlmStreamClient` from `LlmStreamClientProvider.getOrCreate(apiKeyId, vendorName, apiKey)`. Clients are cached by `apiKeyId`.
4. `client.stream(LlmStreamRequest)` returns a `Flux<LlmStreamEvent>`. The orchestrator wraps it to:
   - create an `LlmCallLog` row up front via `LlmCallLogTxService` and emit a synthetic `LogCreated` event,
   - accumulate `OutputItem` / `OutputItemPart` payloads from streamed events (`ResponseMessageAdded`, `ResponseReasoningAdded`, `MessageContentPartDone`, `ReasoningSummaryPartDone`, etc.),
   - capture the determined model from `ResponseCreated` and token usage from `ResponseCompleted`,
   - finalize the log on completion, cancellation, or error (`updateSuccessLog` / `updateCancelledLog` / `updateErrorLog`).
5. Cancellation is cooperative: `LlmStreamCancelRegistry` exposes a `Sinks.One<String>` per `callLogId` so a separate `LlmCallCancelService` can signal cancellation, which `takeUntilOther` then uses to terminate the Flux.

### `:llm-stream-client` module

This module is intentionally vendor-agnostic. Key types live in `mystix.prompt.llm`:

- `LlmStreamClient` — single-method interface returning `Flux<LlmStreamEvent>`.
- `LlmStreamClientProvider` + `LlmClientCreator` registry, wired up in `LlmStreamClientConfiguration` (Spring `@Configuration`). Vendor constants are in `LlmStreamClients` (`VENDOR_OPENAI`, `VENDOR_GOOGLE`, `VENDOR_XAI`).
- Per-vendor implementations under `llm/openai`, `llm/google`, `llm/xai`. OpenAI uses the official `openai-java` SDK; Google and xAI use `WebClient` against raw HTTP endpoints. Each vendor translates its native stream into the shared `LlmStreamEvent` hierarchy.
- Tool/function-call support: `LlmToolSpec`, `ToolDefinition(s)`, `ToolParam`, `ToolCallback`, plus `schema/ToolSchemaGenerator` and `schema/OpenAiToolSchemaNormalizer`. As of recent commits, tool/function-call execution is performed **inside** the stream client — `response.completed` events around function calls are suppressed and tool results are surfaced as their own stream events instead of leaking through to callers. When adding tool features, preserve this encapsulation.
- `FakeLlmStreamClient` is available for tests that need a deterministic stream without hitting a vendor.

### Persistence

JPA + Liquibase. Entities in `mystix.prompt.data.entity` (note `AGENTS.md` preference: regular JPA classes, not `data class`). Repositories in `mystix.prompt.data.repo`. Schema changes go through Liquibase changelogs under `src/main/resources/db/changelog` — do not rely on `ddl-auto=update` for new columns. Enums in `data/Enums.kt` are uppercase by convention.

`LlmCallLogTxService` is the transactional boundary for log writes during streaming (the streaming pipeline itself is non-transactional Reactor code, so each log mutation hops into its own transaction).

### Security / config

- JWT resource server validates Google-issued ID tokens (`application.yml`).
- LLM API keys are encrypted at rest using the AES key in `app.security.api-key-encryption.key`. The dev value in `application.yml` must not be reused in real environments. `ApiKeyCipher` decrypts on the read path.
- Spring config classes in `mystix.prompt.config` cover security, Jackson/HAL shim, paging, and a `tracer-webmvc` integration.

## Conventions worth knowing

- Compiler args (`-Xjsr305=strict`, `-Xannotation-default-target=param-property`) and the `kotlin.plugin.noarg`/`plugin.jpa`/`plugin.spring` plugins are applied at the root and inherited by subprojects. Don't drop them when editing build files.
- Tests use JUnit 5 with `spring.test.constructor.autowire=all` enabled — constructor-injected test classes work out of the box. MockK is the mocking library; MockWebServer covers HTTP fakes; Testcontainers provides PostgreSQL.
- Commit messages are short, imperative, often in Korean (see `git log`). Keep that style.
- Jackson is configured with `default-property-inclusion=non_null`. The `Jackson2HalModuleShim` / `TypeInformationShim` exist to bridge Spring Data REST internals against the newer Jackson 3 (`tools.jackson.*`) module — touch them carefully.
