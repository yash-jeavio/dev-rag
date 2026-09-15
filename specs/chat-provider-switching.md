# Feature Specification: Pluggable Chat Provider Switching

## 1. Goal

Make the app's chat/generation and LLM-judge components provider-agnostic,
so a second chat provider (OpenAI's `gpt-4o-mini`) can sit alongside the
existing Gemini implementation, switchable at runtime via one global
setting — without touching or risking the already-reviewed Gemini code
path, and without hard-coding either provider's name, SDK types, or
exception classes into the business logic that consumes it.

This is deliberately architected so a **third** provider (or removing one
of the two) never requires changing `GenerationService` or `JudgeService`
— only a new provider-specific configuration class and one small addition
to `ChatProviderService`. The motivating trigger was hitting Gemini's
free-tier daily quota during manual testing, but the design goal is
broader: this app should never again be tightly coupled to one vendor's
chat API.

Out of scope: a third provider (Anthropic, etc.) — the design supports
adding one cheaply later, but none is built now (YAGNI). Persisting the
active-provider choice across restarts (see BR-03). Per-request provider
selection (see BR-02) — the toggle is global and shared by everything
that talks to a chat model, not chosen per question.

## 2. Why Provider Selection Cannot Use Spring AI's Own Discriminator Property

Spring AI's `spring.ai.model.chat` property is not a "preference hint" —
it fully gates whether a provider's autoconfiguration class activates at
all. `GoogleGenAiChatAutoConfiguration` is annotated
`@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL,
havingValue = SpringAIModels.GOOGLE_GEN_AI, matchIfMissing = true)` —
confirmed by reading its source directly. Setting this property to one
value prevents the *other* provider's autoconfiguration class from
creating its `ChatModel` bean at all, regardless of which starters are on
the classpath or which API keys are present. Two providers cannot both be
Spring-AI-autoconfigured to coexist through this property; only one can
ever win.

Consequently: `spring.ai.model.chat=google-genai` stays exactly as it is
today — Gemini's autoconfiguration is untouched, zero risk to that
already-reviewed path. OpenAI's `ChatModel` bean is instead **hand-built**
in a small configuration class of our own, using the OpenAI starter's
underlying SDK/property-binding classes as a library, the same way
`RagConfiguration` already hand-builds the `VectorStore` bean rather than
relying on full autoconfiguration for it. This sidesteps the discriminator
property entirely rather than fighting it.

## 3. New/Changed Components — `com.devassist.rag`

No new package. This extends the existing chat-generation area, the same
package `GenerationService`, `RagExceptionHandler`, and
`GeminiLazyChatModelConfiguration` already live in.

| Component | Responsibility |
|---|---|
| `ChatProvider` | `enum { GEMINI, OPENAI }` |
| `OpenAiChatConfiguration` | Hand-builds the `OpenAiChatModel` bean from `OpenAiCommonProperties`/`OpenAiChatProperties` (the real Spring AI 2.0.1 class names, confirmed by reading `OpenAiChatAutoConfiguration`'s source — not `OpenAiConnectionProperties`, which doesn't exist), via `OpenAiChatOptions.builder().apiKey(...).model(...).build()` then `OpenAiChatModel.builder().options(...).build()` — the builder constructs its own `OpenAIClient`/`OpenAIClientAsync` internally when none is supplied, so no manual client wiring is needed. Written by us, not Spring AI's own autoconfiguration class (§2). **Does not fail fast on a missing key** — see §4 for why this differs from Gemini |
| `GeminiLazyChatModelConfiguration` (**unmodified**) | Confirmed by reading its source: its `BeanFactoryPostProcessor` already marks lazy *every* bean of type `org.springframework.ai.chat.model.ChatModel` (the generic Spring AI interface, not a Gemini-specific one) — only its second loop, over `com.google.genai.Client` (Gemini's raw SDK client, exposed as its own bean), is Gemini-specific. Since `OpenAiChatModel implements ChatModel` (confirmed by reading its source) and this plan's `OpenAiChatConfiguration` does not expose OpenAI's raw SDK client as a separate bean (§2 — it's built internally, not injected), the existing generic loop already covers the new provider with no changes to this file at all |
| `ChatProviderService` | Holds the in-memory active `ChatProvider` (default `GEMINI`); constructor-injects `ObjectProvider<GoogleGenAiChatModel>` and `ObjectProvider<OpenAiChatModel>` (both lazy per above); exposes `get()`, `set(ChatProvider)`, `activeChatClientBuilder()` |
| `ChatProviderController` | `GET`/`PUT /api/settings/chat-provider` |
| `GenerationService` (modified) | Constructor changes from `ObjectProvider<ChatClient.Builder>` to `ChatProviderService`; the single call site changes from `.getObject()` to `.activeChatClientBuilder()`. Nothing else in this class changes |
| `JudgeService` (modified) | Same one-line constructor/call-site change as `GenerationService` |

`ChatProviderService` is the *only* component that knows both providers
exist. `GenerationService` and `JudgeService` ask it for "the active"
builder the same way they already ask Spring for a bean today — neither
references `GEMINI` or `OPENAI` by name anywhere in its own logic.

## 4. How This Preserves the Existing Boot-Without-Keys Guarantee

Both providers' real `ChatModel` beans are registered but marked lazy —
Spring knows they exist, but builds neither until first actually needed.
`ChatProviderService` itself is created eagerly, but constructing it does
not resolve either lazy bean; it only holds the deferred `ObjectProvider`
references and the in-memory toggle. The app therefore boots successfully
regardless of whether `GEMINI_API_KEY`, `OPENAI_API_KEY`, both, or neither
are set — extending the exact guarantee `GeminiLazyChatModelConfiguration`
already provides for Gemini alone today, to both providers.

The first time `activeChatClientBuilder()` is called for a given provider,
that provider's bean is actually built for the first time. Every
subsequent call for that same provider reuses the already-built singleton;
switching back and forth between providers never rebuilds anything that
was already built once.

**The two providers do not fail the same way when a key is missing —
confirmed by reading each provider's actual source, not assumed by
analogy.** Gemini's bean-creation method (`googleGenAiClient(...)` in
`GoogleGenAiChatAutoConfiguration`) explicitly checks for a key and throws
`IllegalStateException("Incomplete Google GenAI configuration...")`
immediately if one isn't present — this is a genuine fail-fast at
bean-creation time, wrapped in `BeanCreationException` by Spring, which is
what `RagExceptionHandler` catches today.

OpenAI's setup code (`OpenAiSetup.setupSyncClient`/`setupAsyncClient`) does
something different: if the resolved API key is an empty string (which is
exactly what `${OPENAI_API_KEY:}` produces when the environment variable
is unset), it does **not** throw. It silently builds a working
`OpenAIClient` in a documented "no-auth mode" — a real client whose
`Authorization` header is stripped before every request leaves the JVM.
Bean creation succeeds either way. The failure moves one layer deeper: it
only surfaces when a real chat completion request actually goes out over
the network and OpenAI's server rejects the un-authenticated request
(a 401), not when the bean is built.

Practical consequence: switching to OpenAI with no `OPENAI_API_KEY` set
does **not** give you an immediate, bean-creation-time 503 the way
switching to Gemini-without-a-key does. The app boots fine either way
(BR-01 is unaffected — nothing about "no-auth mode" requires a real
network call at boot), but the *moment* and *shape* of the eventual error
differ by provider. This is exactly why BR-08 treats "OpenAI has no key"
and "OpenAI's live call fails for some other reason (quota, a bad key,
network)" as the same verification task, not two separate ones — for
OpenAI, both are the same failure path.

## 5. API

### 5.1 `GET /api/settings/chat-provider`

```json
{ "provider": "GEMINI" }
```

### 5.2 `PUT /api/settings/chat-provider`

Request:

```json
{ "provider": "OPENAI" }
```

Response, HTTP `200`:

```json
{ "provider": "OPENAI" }
```

Takes effect immediately for every request that starts after this call
returns. A request already in progress when the switch happens finishes
with whichever provider was active when *it* started (BR-04).

An invalid provider string (anything other than `GEMINI`/`OPENAI`) fails
Jackson's enum deserialization before the controller method runs, and is
handled by Spring Boot's own default error handling — HTTP `400`. No
custom exception handler is added for this endpoint (BR-07); this is a
deliberate simplification for a low-stakes internal toggle, not an
oversight.

## 6. UI Change

`src/main/resources/static/index.html` gains a small provider selector
next to the existing "Project: ..." line — a `<select>` with `Gemini` /
`OpenAI` options. On page load, `GET` the current provider and set the
dropdown to match; on change, `PUT` the new value. No page reload; a plain
`fetch` call, consistent with the rest of the page's existing style. This
is an additive change to an existing file, not a new page or component.

## 7. Business Rules

- **BR-01**: The application must boot successfully regardless of whether
  `GEMINI_API_KEY`, `OPENAI_API_KEY`, both, or neither environment
  variables are set. Neither provider's `ChatModel` bean is built until
  the first time it is actually needed (§4).
- **BR-02**: Exactly one chat provider is active at any moment, shared
  globally across both regular Q&A generation (`GenerationService`) and
  the eval harness's LLM-judge scoring (`JudgeService`) — never split
  per-request or per-feature. There is one toggle, not two.
- **BR-03**: The active provider is held in memory only. It resets to
  `GEMINI` on every application restart. No persistence layer (file,
  database, or otherwise) is introduced for this setting.
- **BR-04**: Switching providers takes effect immediately for the next
  request that starts after the switch. A request already in progress
  when the switch happens completes using whichever provider was active
  when it started; there is no mid-request provider change.
- **BR-05**: If the active provider fails — for Gemini, that can happen at
  bean-creation time (missing/invalid key) or call time (quota, auth,
  network); for OpenAI, only at call time, since a missing key does not
  prevent its bean from being built (§4) — the failure surfaces as a
  clear error through the *existing* `RagExceptionHandler`. No automatic
  fallback to the other provider is attempted under any circumstance. The
  user must explicitly switch providers themselves.
- **BR-06**: Neither `GenerationService` nor `JudgeService` (nor any other
  consumer) may reference a specific provider (`GoogleGenAiChatModel`,
  `OpenAiChatModel`, or the `ChatProvider` enum values) directly. Both
  obtain "the active" `ChatClient.Builder` exclusively through
  `ChatProviderService.activeChatClientBuilder()`. This is what makes
  adding, removing, or replacing a provider later a change confined to
  `ChatProviderService` and one configuration class, never to either
  consumer.
- **BR-07**: An invalid value on `PUT /api/settings/chat-provider` returns
  `400` via Spring Boot's default request-body deserialization error
  handling. No custom exception handler is added for this endpoint.
- **BR-08** *(resolved by reading the actual `openai-java` SDK source,
  the same way `com.google.genai.errors.ApiException` was previously
  identified for Gemini — not assumed by analogy)*: every failure the
  OpenAI SDK can produce — `UnauthorizedException` (401, missing or
  invalid key — the failure mode §4 describes for a missing key),
  `RateLimitException` (429, quota), `BadRequestException`,
  `NotFoundException`, `PermissionDeniedException`,
  `UnprocessableEntityException`, `InternalServerException` — extends the
  abstract `com.openai.errors.OpenAIServiceException`, which itself
  extends `com.openai.errors.OpenAIException` (a plain `RuntimeException`
  subclass). Network-level failures (`OpenAIIoException`) extend
  `OpenAIException` directly rather than through the service-exception
  branch. Catching the single root type, `OpenAIException`, therefore
  covers every one of OpenAI's own failure modes in one
  `@ExceptionHandler` entry — mirroring exactly how `ApiException` already
  covers every one of Google's. `RagExceptionHandler`'s existing
  `@ExceptionHandler({ NonTransientAiException.class,
  BeanCreationException.class, ApiException.class })` list gains
  `OpenAIException.class` (import `com.openai.errors.OpenAIException`).
  Whether Spring AI wraps this
  exception in another `RuntimeException` before it reaches the
  controller does not matter — `@ExceptionHandler` already matches
  anywhere in the cause chain, confirmed during the RAG core work for
  Gemini's equivalent case.

## 8. Error Conditions

| Condition | Status | Handling |
|---|---|---|
| Gemini's key missing when its bean is first built | 503 | `BeanCreationException` → existing `RagExceptionHandler.handleAiFailure` (no new code) — unchanged, existing behavior |
| Gemini's live call fails (quota/auth/network) | 503 | `NonTransientAiException`/`ApiException` → existing `RagExceptionHandler.handleAiFailure` (no new code) — unchanged, existing behavior |
| OpenAI's key missing, invalid, quota exceeded, or a network error — all surface at call time, not bean-creation time (§4) | 503 | `com.openai.errors.OpenAIException` (root of every OpenAI SDK failure, BR-08) → `RagExceptionHandler.handleAiFailure` after adding it to the existing `@ExceptionHandler` list |
| Invalid provider value in `PUT /api/settings/chat-provider` | 400 | Spring Boot's default JSON-binding error handling (BR-07) — not routed through `RagExceptionHandler` |

## 9. Configuration

```properties
spring.ai.openai.api-key=${OPENAI_API_KEY:}
spring.ai.openai.chat.model=gpt-4o-mini
```

`spring.ai.model.chat=google-genai` is unchanged (§2). No new
`@ConfigurationProperties` class is needed for the provider toggle itself
— `ChatProviderService`'s in-memory field is not externally configurable,
per BR-03.

## 10. Non-Functional Requirements

- **One new dependency**: `spring-ai-starter-model-openai`, added purely
  for its `OpenAiChatModel`/`OpenAiChatOptions`/`OpenAiCommonProperties`/
  `OpenAiChatProperties` classes (used as a library, per §2) — justified
  under `CLAUDE.md`'s "necessary for the specific feature being
  implemented" rule, since OpenAI support is exactly what this feature
  requests. `CLAUDE.md` should be updated to record OpenAI as a second
  in-scope chat provider, the same way it was updated when Gemini and the
  eval harness were added.
- Constructor injection throughout; immutable records for the request/
  response DTOs; thin controller.
- No changes to `JudgeService`'s or `GenerationService`'s actual prompting
  logic (system prompt, options, parsing) — only to how each obtains its
  `ChatClient.Builder`.
- No changes to `RagQueryService`, `DocumentService`, `ProjectService`, or
  anything in `com.devassist.document`/`com.devassist.project`.

## 11. Testing

Tests must pass with no Ollama, no Gemini key, no OpenAI key, and no
network access — same standing rule as the RAG core and the eval harness.

- **`ChatProviderServiceTest`**: default active provider is `GEMINI`;
  `set`/`get` round-trip; `activeChatClientBuilder()` resolves the
  `ObjectProvider` matching the current toggle state (mock both
  providers' `ObjectProvider`s, verify only the correct one's
  `getObject()` is invoked for each toggle value).
- **`ChatProviderControllerTest`** (`@WebMvcTest`): `GET` reflects current
  state; `PUT` updates it and returns confirmation; `PUT` with an invalid
  provider string returns `400` (BR-07).
- **`OpenAiChatModelStartsWithoutApiKeyTest`**: a full `@SpringBootTest`
  context load succeeds with no `OPENAI_API_KEY` set — mirrors the
  existing `GeminiChatModelStartsWithoutApiKeyTest`. This proves the
  lazy-bean mechanism correctly extends to the new provider (BR-01) — it
  is purely about the bean staying unbuilt until something asks for it,
  and is unaffected by §4's no-auth-mode finding (that only matters once
  something actually calls `activeChatClientBuilder()` and tries to use
  OpenAI for real, which BR-08's verification work covers separately).
- **`GenerationServiceTest`, `JudgeServiceTest`** (existing, migrated):
  mocking setup moves from `ObjectProvider<ChatClient.Builder>` to
  `ChatProviderService`; every existing assertion these tests already make
  must continue to pass unchanged — this is mechanical migration of how
  the chat client is stubbed, not new test logic.

Run `./mvnw test` after implementation.
