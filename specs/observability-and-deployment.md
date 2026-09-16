# Feature Specification: API Observability, Error-Response Consistency, and Docker Compose Deployment

## 1. Goal

Close out the three remaining pieces of the "Day 15" milestone on top of the
already-complete RAG core: give the query and summarization pipelines actual
observability (per-stage latency and token usage, via Micrometer/Actuator
rather than ad-hoc response fields), tighten the four existing exception
handlers onto one consistent, typed error shape, and make the whole
application runnable with a single `docker compose up` — including Ollama —
so it no longer depends on a manually-configured local environment.

Out of scope: any change to retrieval/generation *behavior* (this is
observability and packaging only); authentication/authorization (explicitly
out of scope for the whole project per `CLAUDE.md`); PGVector or any
persistence change; exposing metrics to an external system (Prometheus,
Grafana) — `/actuator/metrics`'s built-in JSON view is sufficient for this
milestone.

## 2. Part A — Observability (Micrometer / Actuator)

### 2.1 Why this shape

`RagAnswerResponse` already carries a single end-to-end `latencyMs` field,
but nothing distinguishes retrieval time from generation time, and nothing
tracks token consumption at all. Two ways to add this were considered:
extending `RagAnswerResponse` with more fields (simple, no new dependency,
but only visible per-query), or proper Micrometer timers/counters exposed
via Spring Boot Actuator (aggregate, queryable, the standard observability
mechanism, at the cost of one new dependency). This spec uses the Micrometer
path.

Confirmed directly against the real Spring AI 2.0.1 API on this project's
classpath (`spring-ai-client-chat`, `spring-ai-model` jars): `ChatClient`'s
`CallResponseSpec` exposes `chatResponse()` returning a full `ChatResponse`,
whose `getMetadata().getUsage()` returns a `Usage` object with
`getPromptTokens()`, `getCompletionTokens()`, and a default
`getTotalTokens()` — normalized identically regardless of which provider
(Gemini or OpenAI) produced the response. This means token usage can be
captured with **zero change to any public method signature**: the two
services that call a chat model (`GenerationService`, `SummaryGenerator`)
switch their internal call from `.call().content()` to `.call().chatResponse()`,
extract the answer text from `chatResponse.getResult().getOutput().getText()`,
and extract usage from `chatResponse.getMetadata().getUsage()` — their
`generate(...)`/`summarize(...)` methods still return a plain `String`, so no
caller anywhere needs to change.

### 2.2 New dependency

`spring-boot-starter-actuator` (Maven, version controlled by the parent BOM
per `CLAUDE.md` §7 — no explicit `<version>`). This is the one new
dependency introduced by this spec, and it is necessary: Micrometer's core
API already rides in transitively via Spring AI's observation modules, but
without Actuator there is no `MeterRegistry` bean and no `/actuator/metrics`
endpoint to read anything back from.

`application.properties` gains:

```properties
management.endpoints.web.exposure.include=metrics,health
```

(Actuator only exposes `/health` by default; `metrics` must be opted in
explicitly.)

### 2.3 Changed components — `com.devassist.rag`

| File | Change |
|---|---|
| `RetrievalService` | Constructor gains a `MeterRegistry` parameter. Wraps the existing `vectorStore.similaritySearch(request)` call in a `Timer.Sample`, recording it to `rag.retrieval.duration` on completion. No tags — retrieval depends on the embedding model (fixed to Ollama today), not the switchable chat provider. |
| `GenerationService` | Constructor gains a `MeterRegistry` parameter. Internally switches from `.call().content()` to `.call().chatResponse()`. Records `rag.generation.duration` (Timer, tag `provider=gemini\|openai`) around the model call, and `rag.generation.tokens` (Counter, tags `provider`, `type=prompt\|completion`) from the returned `Usage`. Public method signature (`generate(String context, String question) -> String`) is unchanged. |
| `SummaryGenerator` | Identical treatment to `GenerationService`: `MeterRegistry` injected, internal switch to `.call().chatResponse()`, records `rag.summarization.duration` and `rag.summarization.tokens` with the same tag shape. Public signature (`summarize(String text, String instruction) -> String`) is unchanged. |

The provider tag's value comes from `chatProviderService.get()` (already
injected into both services), converted to lowercase (`gemini`/`openai`) to
match Micrometer's tag-naming convention (lowercase, dot-separated metric
names; lowercase tag values).

### 2.4 Metrics catalog

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `rag.retrieval.duration` | Timer | *(none)* | Time spent in `VectorStore.similaritySearch(...)` per query. |
| `rag.generation.duration` | Timer | `provider` | Time spent in the chat model call per query answer. |
| `rag.generation.tokens` | Counter | `provider`, `type` (`prompt`\|`completion`) | Tokens consumed per query answer, incremented by the count from each call's `Usage`. |
| `rag.summarization.duration` | Timer | `provider` | Time spent in the chat model call per summarization request. |
| `rag.summarization.tokens` | Counter | `provider`, `type` (`prompt`\|`completion`) | Tokens consumed per summarization request. |

`getTotalTokens()` is not emitted as a separate counter — it is always
`prompt + completion`, so a consumer of `/actuator/metrics` computing a
total from the two tagged counters avoids a metric that could silently
drift out of sync with its own components.

### 2.5 Business Rules

- **BR-01**: Every call to `GenerationService.generate(...)` or
  `SummaryGenerator.summarize(...)` records exactly one `duration` timer
  observation and exactly two `tokens` counter increments (one for
  `type=prompt`, one for `type=completion`), regardless of which provider
  is active.
- **BR-02**: A failed model call (the provider throws before returning a
  `ChatResponse` — e.g. `NonTransientAiException`, `ApiException`,
  `OpenAIException`) still propagates the exception unchanged to
  `RagExceptionHandler`/callers; no metric is recorded for a call that
  never produced a response, since there is no `Usage` to read and no
  completed duration to attribute a provider to.
- **BR-03**: `RetrievalService`'s timer records unconditionally, whether or
  not the search returns any chunks — an empty result is still a completed
  retrieval, not a failure.
- **BR-04**: Metric emission never blocks or fails the request it is
  measuring — a `MeterRegistry` call is synchronous, in-process, and has no
  external dependency to fail.

## 3. Part B — Error-Response Consistency

### 3.1 Current state

All four `@RestControllerAdvice` classes (`DocumentExceptionHandler`,
`ProjectExceptionHandler`, `RagExceptionHandler`, `EvalExceptionHandler`)
already return the same de-facto JSON shape — `{"status": ..., "message":
...}` or `{"status": ..., "errors": [...]}` — but each builds it with a raw
`Map.of(...)`, so nothing enforces that the shape stays consistent as new
handlers are added. The `MethodArgumentNotValidException` handler is
duplicated verbatim across `DocumentExceptionHandler`,
`ProjectExceptionHandler`, and `RagExceptionHandler`. No handler in any of
the four classes has a fallback for an exception type it doesn't explicitly
list — an unanticipated exception falls through to Spring Boot's default
`/error` handling (safe today only because `server.error.include-stacktrace`
defaults to `never`; `security-review.txt`, written earlier in this
project's history, already flagged the absence of an explicit catch-all as
a Low-severity gap).

`EvalController` has exactly one endpoint (`POST /api/eval/run`) and it
takes no request body, so `EvalExceptionHandler` has no
`MethodArgumentNotValidException` case to begin with — its current shape is
correct, not incomplete.

### 3.2 New component — `com.devassist.common`

A new, minimal package holding one file:

```java
package com.devassist.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String message, List<FieldError> errors) {

	public record FieldError(String field, String message) {
	}

	public static ErrorResponse of(HttpStatus status, String message) {
		return new ErrorResponse(status.value(), message, null);
	}

	public static ErrorResponse validationFailure(MethodArgumentNotValidException ex) {
		List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> new FieldError(fieldError.getField(), fieldError.getDefaultMessage()))
				.toList();
		return new ErrorResponse(HttpStatus.BAD_REQUEST.value(), null, errors);
	}
}
```

`@JsonInclude(NON_NULL)` is what keeps the JSON shape byte-identical to
today's `Map.of(...)`-based responses: a `message`-only response never
serializes an `errors: null` field, and vice versa. `com.devassist.common`
depends on nothing in `document`/`project`/`rag`/`eval`, preserving the
one-way dependency rule `EvalExceptionHandler`'s own existing comment
documents (`eval` depends on `rag`, never the reverse — this new package
sits below all four, not beside `eval`).

### 3.3 Changed components

| File | Change |
|---|---|
| `DocumentExceptionHandler` | All 5 handler methods return `ErrorResponse` instead of `Map<String,Object>`. `handleValidation` delegates to `ErrorResponse.validationFailure(ex)`. Gains a new `@ExceptionHandler(Exception.class)` → 500 returning `ErrorResponse.of(INTERNAL_SERVER_ERROR, "An unexpected error occurred")`. |
| `ProjectExceptionHandler` | Same treatment: 2 existing handlers converted, `handleValidation` delegates, new catch-all added. |
| `RagExceptionHandler` | Same treatment: 3 existing handlers converted (`handleNotFound`, `handleValidation`, `handleAiFailure`), `handleValidation` delegates, new catch-all added. `deepestMessage(...)` is untouched — `handleAiFailure` still uses it to build the `message` string passed into `ErrorResponse.of(...)`. |
| `EvalExceptionHandler` | Its one existing handler (`handleCorpusNotReady`) converts to `ErrorResponse`. Gains the new catch-all. No validation handler is added (§3.1). |

### 3.4 Business Rules

- **BR-05**: For every endpoint in the application, an exception with no
  specific handler now returns HTTP 500 with body
  `{"status": 500, "message": "An unexpected error occurred"}` instead of
  falling through to Spring Boot's default `/error` page — and never
  includes the original exception's message or stack trace, so an
  unanticipated internal failure cannot leak implementation details to a
  caller.
- **BR-06**: Every existing, already-tested error response's JSON shape is
  byte-identical before and after this change — this is a response-object
  refactor, not a contract change. No existing controller test's
  `jsonPath(...)` assertions should need to change.
- **BR-07**: The one-way dependency rule (`eval` → `rag`; no package
  depends on a sibling feature package) is preserved. `com.devassist.common`
  is a leaf: nothing in it imports from `document`, `project`, `rag`, or
  `eval`.

## 4. Part C — Docker Compose Deployment

### 4.1 Goal

`docker compose up` in a fresh checkout, with a `.env` file supplying API
keys, should bring up a fully working instance — app, embeddings, and
model — with no separately-installed local Ollama, JDK, or Maven required.

### 4.2 New files

| File | Purpose |
|---|---|
| `Dockerfile` | Multi-stage build. Stage 1 (`eclipse-temurin:17-jdk-jammy`): copies `pom.xml`, `.mvn/`, `mvnw` first (for Docker layer caching on dependency resolution), then `src/`, then runs `./mvnw clean package -DskipTests` — the project's own wrapper, per `CLAUDE.md` §9 ("Maven Wrapper only"), not a pre-installed Maven in the base image. Tests already run as part of the normal development workflow (`CLAUDE.md` §8); re-running the full suite on every image build only slows down the packaging step without adding new verification. Stage 2 (`eclipse-temurin:17-jre-jammy`, slim): copies the built jar from stage 1, `ENTRYPOINT ["java", "-jar", "app.jar"]`, exposes port 8080. |
| `.dockerignore` | Excludes `target/`, `.git/`, `.idea/`, `.claude/`, `docs/`, `specs/`, `*.md` from the build context so the image build doesn't ship or hash irrelevant files. |
| `docker-compose.yml` | Three services (§4.3). |
| `.env.example` | Committed template listing `GEMINI_API_KEY=` and `OPENAI_API_KEY=` with no values — documents what `docker compose up` expects in a real (gitignored) `.env` file. |

`.gitignore` gains a `.env` entry (the template stays `.env.example`,
tracked; the real file with actual keys never is).

### 4.3 `docker-compose.yml` services

- **`ollama`**: official `ollama/ollama` image. Named volume
  (`ollama_data:/root/.ollama`) so a pulled model survives
  `docker compose down` (without `-v`) and doesn't re-download on every
  restart.
- **`ollama-pull`**: same image, `depends_on: ollama`, entrypoint runs
  `ollama pull nomic-embed-text` against the `ollama` service and then
  exits — this is what makes the whole stack self-contained instead of
  requiring a manual `docker compose exec ollama ollama pull ...` step
  after first start.
- **`app`**: builds from the repo's `Dockerfile`. `depends_on: ollama-pull`
  with `condition: service_completed_successfully`, so the app never starts
  racing an empty model. Environment: `SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434`
  (Spring Boot's relaxed property binding maps this environment variable
  onto `spring.ai.ollama.base-url`, overriding the `application.properties`
  default of `http://localhost:11434`, which is unreachable from inside the
  `app` container's own network namespace); `GEMINI_API_KEY`/`OPENAI_API_KEY`
  passed through from the `.env` file docker-compose loads automatically.
  Port `8080:8080` published to the host.

### 4.4 Business Rules

- **BR-08**: No API key is ever baked into the built image — both keys
  arrive only as container environment variables at `docker compose up`
  time, sourced from a gitignored `.env` file, per `CLAUDE.md`'s existing
  "never hardcode secrets" rule.
- **BR-09**: A fresh `docker compose up` (no prior state, empty
  `ollama_data` volume) succeeds in bringing the whole stack up without any
  manual step beyond providing `.env` — the embedding model is pulled
  automatically before the app becomes reachable.
- **BR-10**: `docker compose down -v` fully tears down all state (including
  the pulled model); a subsequent `docker compose up` re-pulls it from
  scratch and still succeeds. This is the manual regression check for BR-09.

### 4.5 Testing

No automated test — this project has no CI/build-time Docker verification
today, and infra configuration isn't unit-testable the way application code
is (the same reasoning `docs/superpowers/plans/*.md`'s own UI tasks have
already applied to `index.html`). Verification is manual:

1. `cp .env.example .env` and fill in a real `GEMINI_API_KEY` (or
   `OPENAI_API_KEY`).
2. `docker compose up` from a clean state (`docker compose down -v` first
   if anything was left over from a previous run).
3. Confirm `ollama-pull` completes and exits 0 before `app` starts.
4. Upload a document and ask a question through the running container
   (`http://localhost:8080`), confirming a real, grounded answer comes
   back — proving the app container, Ollama container, and configured chat
   provider are all correctly wired together.
5. `docker compose down -v`, then repeat steps 2-4 once more from a
   genuinely empty state, confirming BR-09/BR-10 hold and nothing was only
   working because of leftover state from the first run.

## 5. Non-Functional Requirements

- No change to authentication/authorization — unchanged from the rest of
  the project's declared scope (`CLAUDE.md` §9).
- No change to `RagQueryService`, `RagAnswerResponse`, `RagQueryController`,
  or any controller-facing DTO — Part A is entirely internal to the three
  services listed in §2.3, and Part B changes only the *type* returned by
  exception handlers, not the JSON they produce (BR-06).
- `com.devassist.eval.EvaluationService`/`JudgeService` are untouched by
  Part A (they call `GenerationService`'s `ChatClient.Builder` a
  different way than the query/summarization paths and are out of this
  spec's scope) and by Part B beyond `EvalExceptionHandler`'s own return
  type.
- Constructor injection and immutable records maintained throughout — no
  deviation from existing conventions.
- The one new dependency (`spring-boot-starter-actuator`) is justified per
  §2.2; no other dependency is added.

## 6. Testing Summary

- **Part A**: `RetrievalServiceTest`, `GenerationServiceTest`,
  `SummaryGeneratorTest` (all existing) extended with a mocked/real
  `MeterRegistry` (Micrometer ships a `SimpleMeterRegistry` for exactly
  this purpose — no mocking framework needed) asserting the correct
  metric name, tag values, and that a timer/counter was actually recorded
  (not just that the call succeeded).
- **Part B**: `DocumentExceptionHandlerTest`,
  `ProjectExceptionHandlerTest`/existing controller tests,
  `RagExceptionHandlerTest`/existing controller tests, and
  `EvalExceptionHandlerTest`/existing controller tests (existing, via
  `@WebMvcTest` + `jsonPath(...)`, per BR-06) confirmed unchanged; new
  tests added for each handler's new catch-all
  (`Exception.class` → 500, generic message, no leaked details).
- **Part C**: manual only, per §4.5.

Run `./mvnw test` after implementation, per project convention.
