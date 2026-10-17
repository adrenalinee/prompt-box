# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

PromptBox is a Spring Boot 4 / Kotlin 2.2 (JDK 21) backend for testing and managing LLM prompts: a streaming playground across vendors (OpenAI, Google, xAI), named and versioned prompts, LLM call logs, and workspaces that scope data and default options. Code comments and commit messages are in Korean. The frontend lives elsewhere (CORS allows `http://localhost:3030`).

## Build & test

Gradle multi-project build: the root app `prompt-box` plus the `llm-stream-client` library subproject.

```bash
./gradlew build -x test                        # build (Dockerfile does this; jar is build/libs/prompt-box.jar)
./gradlew test                                 # all tests
./gradlew :llm-stream-client:test              # library tests only (no Docker/DB needed)
./gradlew test --tests "malibu.llm.prompt.ExecuteStreamingSuccessTest"          # one class
./gradlew test --tests "malibu.llm.prompt.ExecuteStreamingSuccessTest.someName" # one method
./gradlew bootRun                              # run on port 7070
```

- Dependency resolution needs GitHub Packages credentials (`malibu.tracer:tracer-webmvc` comes from `maven.pkg.github.com/adrenalinee/tracer`). Supply them as the `gpr.user`/`gpr.key` Gradle properties or the `USERNAME`/`TOKEN` env vars.
- Root-app tests need Docker: they use Testcontainers Postgres 17, either through `TestcontainersConfig` or the `jdbc:tc:` URL in `src/test/resources/application.properties`. Tests run with `ddl-auto=create-drop` and Liquibase disabled.
- Test constructor injection is enabled (`spring.test.constructor.autowire=all`), so test classes take beans as constructor params.
- The default profile runs on a file-based H2 database (`./h2data/`, PostgreSQL mode). Liquibase is off and Hibernate `ddl-auto=update` builds the schema. The `postgres` profile uses `localhost:5432/prompt`. The `dev` profile reads `POSTGRESQL_URL`/`POSTGRESQL_USER`/`POSTGRESQL_PASSWORD`. Both Postgres profiles enable Liquibase.

## Architecture

### `llm-stream-client` (library, `malibu.llm.streamclient`)
A vendor-neutral streaming abstraction, published separately to GitHub Packages (`version` lives in its `build.gradle.kts`).
- `LlmStreamClient.stream(LlmStreamRequest): Flux<LlmStreamEvent>`. `LlmStreamEvent` is a sealed hierarchy modeled on the OpenAI Responses API event lifecycle (ResponseCreated → item/part Added → Delta → Done → ResponseCompleted, plus reasoning and function-call events). Every vendor client maps its native stream onto these events.
- `LlmStreamClientProvider` caches one client per **API key id** and builds clients through per-vendor creator lambdas. They are registered in `LlmStreamClientConfiguration` under the vendor names `openai`/`google`/`xai` (`LlmStreamClients`), which must match `LlmVendor.name` in the DB.
- Vendor implementations:
  - `openai/OpenaiLlmStreamClient` uses the official `openai-java` SDK.
  - `google/GoogleLlmStreamClient3` is the active Google client and uses the Interactions API via WebClient. `GoogleLlmStreamClient` (generateContent) is the older one; its registration is commented out.
  - `xai/XaiLlmStreamClient` uses the Responses API via WebClient.
- Tool calling: `ToolCallback`/`ToolDefinition`s plus `schema/ToolSchemaGenerator` generate JSON schemas, and `OpenAiToolSchemaNormalizer` makes them strict-mode compatible. Clients run multi-round tool loops internally. `StreamSession` makes all rounds look like one response to the consumer: one ResponseCreated, one final ResponseCompleted carrying the accumulated usage, and item indexes/sequences that keep increasing across rounds.
- `LlmStreamCancelRegistry` maps a call-log id to a cancel sink. `FakeLlmStreamClient` is available for tests.

### Root app (`malibu.llm.prompt`)
- **Execution flow:** `POST /workspaces/{id}/llm-calls` (`WorkspacesController`, SSE `text/event-stream`) goes to `ExecuteStreamingOrchestratorImpl`, which:
  1. validates that the workspace, model, API key, and vendor match;
  2. resolves instructions from a `PromptRef` (a prompt version) or raw text, plus additional input items;
  3. merges workspace `DefaultLlmCallOptions` with the request overrides and filters them by `LlmModelType`;
  4. decrypts the API key (`ApiKeyCipher`, AES key at `app.security.api-key-encryption.key`) and streams from the provider client.

  The orchestrator emits a `LogCreated` event first, persists output items, parts, and token usage to `LlmCallLog` through `LlmCallLogTxService`, and supports cancellation through `LlmCallCancelService`/the cancel registry.
- **Layers:** `api/` holds the REST controllers, `service/` the transactional services, and `data/entity` + `data/repo` the JPA entities and Spring Data repositories (spring-data-rest is also on the classpath). Domain exceptions live in `error/Exceptions.kt` and map to HTTP responses in `ApiExceptionHandler`.
- **Schema:** Liquibase SQL changesets live in `src/main/resources/db/changelog/changes/NNN_*.sql`, listed in `db.changelog-master.xml`. Add a new numbered changeset for schema changes. Changesets are PostgreSQL-only (012 uses a PL/pgSQL `DO` block), so they don't run on H2. H2 relies on `ddl-auto=update` from the entities, so keep entities and changesets in sync.
- **Security:** an OAuth2 resource server validating Google-issued JWTs. Everything except Swagger (`/swagger-ui`, `/api-docs`) needs authentication.
- **Jackson:** the app is mostly on Jackson 3 (`tools.jackson.*`). The `com.fasterxml` imports are still used where the OpenAI SDK and Swagger require them.
- `EntityManagerExceptionTranslationConfig` (enabled in tests via `app.exception-translation.em.override`) wraps the EntityManager so that constraint violations surface as `DataIntegrityViolationException`.

### Testing patterns
- Orchestrator tests (`ExecuteStreaming*Test`) use `@SpringBootTest` with `TestcontainersConfig` + `ExecuteStreamingTestConfig`. The latter provides a `@Primary` `LlmStreamClientProvider` whose `OPENAI-TEST` vendor returns `TestLlmStreamClient`; set `nextFlux` to script the events.
- Vendor client tests in `llm-stream-client` use OkHttp `MockWebServer` and MockK.

### Non-source directories
The top-level `dev/` (langchain4j Java sources) and `main/` (openai-java Kotlin sources) are reference copies of third-party code. They are **not** part of any Gradle source set and are not compiled.
