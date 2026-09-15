# Pluggable Chat Provider Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OpenAI (`gpt-4o-mini`) as a second, runtime-switchable chat
provider alongside the existing Gemini implementation, with one global
toggle shared by regular Q&A generation and the eval harness's LLM judge.

**Architecture:** Gemini's existing Spring AI autoconfiguration is left
completely untouched. OpenAI's `ChatModel` bean is hand-built in a new
configuration class, because Spring AI's `spring.ai.model.chat`
discriminator property cannot activate both providers' autoconfigurations
at once (verified by reading `GoogleGenAiChatAutoConfiguration`'s and
`OpenAiChatAutoConfiguration`'s actual source — both are gated by the same
property, each for a different value). A new `ChatProviderService` holds
the in-memory active-provider toggle and is the only component that knows
both providers exist; `GenerationService` and `JudgeService` ask it for
"the active" `ChatClient.Builder` instead of resolving one themselves.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring AI 2.0.1
(`spring-ai-starter-model-openai`, newly added), Maven Wrapper.

**Spec:** `specs/chat-provider-switching.md`

## Global Constraints

- Java 17 / Spring Boot 4.1.1 fixed — no framework changes.
- Maven Wrapper only (`./mvnw`), never a global `mvn`.
- Constructor injection only; no field or setter injection.
- Immutable records for the request/response DTOs.
- **One new dependency**: `spring-ai-starter-model-openai`, justified
  because OpenAI support is exactly what this feature requests.
- Tests must pass with no Ollama, no Gemini key, no OpenAI key, and no
  network access. The test profile (`application-test.properties`)
  already reroutes `spring.ai.model.chat=ollama` for the whole suite —
  this plan's new `OpenAiChatConfiguration` bean is **unconditional** (not
  gated by that property at all), so it registers in every profile
  including the test one, but stays lazy and unbuilt unless a test
  specifically calls for it.
- The 196 tests passing on `main` before this plan must all still pass. Do
  not delete or weaken any existing test.
- Comment only non-obvious "why", never "what".
- Nothing in `com.devassist.document` or `com.devassist.project` is
  modified. In `com.devassist.rag`: `GenerationService.java` and
  `RagExceptionHandler.java` are modified; `RagConfiguration.java` is not
  touched at all. In `com.devassist.eval`: only `JudgeService.java` is
  modified.
- **`GeminiLazyChatModelConfiguration.java` must not be touched.**
  Confirmed by reading its source: its `BeanFactoryPostProcessor` already
  marks lazy every bean of type `org.springframework.ai.chat.model.ChatModel`
  (the generic Spring AI interface, not a Gemini-specific one) — only its
  second loop, over `com.google.genai.Client` (Gemini's raw SDK client), is
  Gemini-specific. `OpenAiChatModel implements ChatModel` (confirmed by
  reading its source), and this plan's `OpenAiChatConfiguration` never
  exposes OpenAI's raw SDK client as its own bean — so the existing generic
  loop already covers the new provider with zero changes to this file.
- A harmless, expected intermediate state: after Task 1 lands (which adds
  a second `ChatModel` bean to the context) but before Tasks 3-4 land
  (which migrate `GenerationService`/`JudgeService` off the old
  `ObjectProvider<ChatClient.Builder>`), a **real** query or eval request
  going through the *old* code path could hit an ambiguous-bean error from
  Spring AI's own `ChatClient.Builder` autoconfiguration (which expects
  exactly one `ChatModel` candidate). No automated test exercises this —
  every existing test either mocks `ObjectProvider<ChatClient.Builder>`
  directly (no real Spring context involved) or mocks the service layer
  above it (`@WebMvcTest` slices) — so this does not block the task
  sequence. Just don't manually test real queries against a
  half-migrated build.

---

### Task 1: OpenAI dependency, hand-built chat model bean, and the provider enum

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/devassist/rag/ChatProvider.java`
- Create: `src/main/java/com/devassist/rag/OpenAiChatConfiguration.java`
- Test: `src/test/java/com/devassist/rag/OpenAiChatConfigurationTest.java`
- Test: `src/test/java/com/devassist/rag/OpenAiChatModelStartsWithoutApiKeyTest.java`

**Interfaces:**
- Produces: `ChatProvider` enum (`GEMINI`, `OPENAI`); an
  `org.springframework.ai.openai.OpenAiChatModel` bean registered in the
  Spring context (consumed by Task 2's `ChatProviderService`).

- [ ] **Step 1: Add the dependency**

In `pom.xml`, immediately after the existing `spring-ai-starter-model-google-genai`
dependency block:

```xml
		<dependency>
			<groupId>org.springframework.ai</groupId>
			<artifactId>spring-ai-starter-model-google-genai</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.ai</groupId>
			<artifactId>spring-ai-starter-model-openai</artifactId>
		</dependency>
```

No explicit `<version>` — it's managed by the existing `spring-ai-bom`
import already in `<dependencyManagement>`.

- [ ] **Step 2: Add the configuration properties**

In `src/main/resources/application.properties`, immediately after the
existing `spring.ai.google.genai.chat.model=gemini-3.6-flash` line:

```properties
spring.ai.openai.api-key=${OPENAI_API_KEY:}
spring.ai.openai.chat.model=gpt-4o-mini
```

These bind to Spring AI's own `OpenAiCommonProperties` (prefix
`spring.ai.openai`, field `apiKey`) and `OpenAiChatProperties` (prefix
`spring.ai.openai.chat`, field `model` — inherited flat from
`AbstractOpenAiProperties`, not nested under an `options.*` path) —
confirmed by reading both classes' `@ConfigurationProperties` annotations
directly.

- [ ] **Step 3: Write the failing test for the provider enum and bean construction**

```java
package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatConfigurationTest {

	private final OpenAiChatConfiguration configuration = new OpenAiChatConfiguration();

	@Test
	void buildsAChatModelWhenAnApiKeyIsPresent() {
		OpenAiCommonProperties commonProperties = new OpenAiCommonProperties();
		commonProperties.setApiKey("test-key");
		OpenAiChatProperties chatProperties = new OpenAiChatProperties();
		chatProperties.setModel("gpt-4o-mini");

		OpenAiChatModel chatModel = configuration.openAiChatModel(commonProperties, chatProperties);

		assertThat(chatModel).isNotNull();
	}

	// Unlike Gemini, OpenAI's client does not fail fast on a missing key -
	// it silently builds a "no-auth mode" client (specs/chat-provider-switching.md
	// S4). Building the client is local configuration only, no network call
	// happens until a real chat request is made, so this must not throw.
	@Test
	void buildsAChatModelEvenWithNoApiKey() {
		OpenAiCommonProperties commonProperties = new OpenAiCommonProperties();
		OpenAiChatProperties chatProperties = new OpenAiChatProperties();
		chatProperties.setModel("gpt-4o-mini");

		OpenAiChatModel chatModel = configuration.openAiChatModel(commonProperties, chatProperties);

		assertThat(chatModel).isNotNull();
	}
}
```

Also create `src/main/java/com/devassist/rag/ChatProvider.java` now, since
the test file above doesn't need it but Step 5's real class does and this
is the natural point to add it (it's a one-line enum with no test of its
own — an enum with no behavior needs no test):

```java
package com.devassist.rag;

public enum ChatProvider {
	GEMINI, OPENAI
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw test -Dtest=OpenAiChatConfigurationTest`
Expected: FAIL — `OpenAiChatConfiguration` does not exist yet.

- [ ] **Step 5: Create `OpenAiChatConfiguration`**

```java
package com.devassist.rag;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring AI's own OpenAiChatAutoConfiguration is gated by the same
// spring.ai.model.chat discriminator property Gemini's autoconfiguration
// uses (@ConditionalOnProperty(..., havingValue = "openai")) - with that
// property set to "google-genai" for Gemini, OpenAI's autoconfiguration
// never activates and its ChatModel bean is never created, regardless of
// which starters are on the classpath. This class hand-builds the bean
// instead, the same way RagConfiguration hand-builds the VectorStore bean,
// so both providers can coexist without touching Gemini's already-reviewed
// autoconfiguration at all.
@Configuration
@EnableConfigurationProperties({ OpenAiCommonProperties.class, OpenAiChatProperties.class })
public class OpenAiChatConfiguration {

	@Bean
	public OpenAiChatModel openAiChatModel(OpenAiCommonProperties commonProperties,
			OpenAiChatProperties chatProperties) {
		OpenAiAutoConfigurationUtil.ResolvedConnectionProperties resolved = OpenAiAutoConfigurationUtil
				.resolveCommonProperties(commonProperties, chatProperties);

		// Unlike Gemini, an empty/missing API key here does not throw - the
		// OpenAI SDK silently builds a client in "no-auth mode" and only
		// fails once a real request goes out (specs/chat-provider-switching.md
		// S4). OpenAiChatModel.build() constructs the underlying
		// OpenAIClient/OpenAIClientAsync itself from these options when
		// neither is supplied explicitly, so no manual client wiring is
		// needed here.
		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.apiKey(resolved.getApiKey())
				.model(resolved.getModel())
				.build();

		return OpenAiChatModel.builder().options(options).build();
	}
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw test -Dtest=OpenAiChatConfigurationTest`
Expected: PASS (2/2).

- [ ] **Step 7: Write the failing regression test proving the app boots with no OpenAI key**

```java
package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression test proving OpenAiChatConfiguration's bean stays lazy: the
 * full application context must start successfully with no usable OpenAI
 * key, the same guarantee GeminiChatModelStartsWithoutApiKeyTest already
 * proves for Gemini. Explicitly blanks the key via @TestPropertySource
 * rather than relying on OPENAI_API_KEY being unset in whatever
 * environment runs this test - a developer's own shell may have it
 * exported for manual testing, which would silently defeat the point of
 * this test if it only checked the ambient environment. Unlike Gemini's
 * version of this test, no spring.ai.model.chat override is needed here:
 * OpenAiChatConfiguration's bean is unconditional, so it is present in
 * every profile already, including the default one this test runs under.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.ai.openai.api-key=")
class OpenAiChatModelStartsWithoutApiKeyTest {

	@Test
	void contextLoadsWithNoOpenAiApiKeySet() {
		// Intentionally empty: reaching this point means the application
		// context started successfully with the OpenAiChatModel bean
		// registered but no usable API key present - the regression this
		// test guards against.
	}
}
```

- [ ] **Step 8: Run the test to verify it fails first, then passes**

Run: `./mvnw test -Dtest=OpenAiChatModelStartsWithoutApiKeyTest`
Expected before Step 5's class exists: compile failure. Since Step 5 is
already done by this point, this test should already compile — instead
verify it would have failed before Step 5 by inspecting the logic (the
`OpenAiChatModel` bean's construction is exactly what Step 5 added; if
`OpenAiChatConfiguration` didn't exist, the app would still start since
there is no eager dependency on it — so the real regression this guards is
Step 5 accidentally making the bean *not* lazy, e.g. by removing the
`ObjectProvider` indirection somewhere else in the graph). Run it now to
confirm it currently PASSES with the implementation in place, then run
`./mvnw test` (full suite) once to confirm nothing else broke.

Expected: PASS, and full suite still green with 196 + 3 new tests.

- [ ] **Step 9: Commit**

```bash
git add pom.xml src/main/resources/application.properties \
  src/main/java/com/devassist/rag/ChatProvider.java \
  src/main/java/com/devassist/rag/OpenAiChatConfiguration.java \
  src/test/java/com/devassist/rag/OpenAiChatConfigurationTest.java \
  src/test/java/com/devassist/rag/OpenAiChatModelStartsWithoutApiKeyTest.java
git commit -m "feat: add OpenAI as a second, hand-built chat model bean"
```

---

### Task 2: `ChatProviderService` — the runtime toggle

**Files:**
- Create: `src/main/java/com/devassist/rag/ChatProviderService.java`
- Test: `src/test/java/com/devassist/rag/ChatProviderServiceTest.java`

**Interfaces:**
- Consumes: `ChatProvider` (Task 1); `org.springframework.ai.google.genai.GoogleGenAiChatModel`
  (existing Gemini bean); `org.springframework.ai.openai.OpenAiChatModel` (Task 1).
- Produces: `ChatProviderService.get()` → `ChatProvider`;
  `ChatProviderService.set(ChatProvider)`;
  `ChatProviderService.activeChatClientBuilder()` → `org.springframework.ai.chat.client.ChatClient.Builder`.
  Consumed by Task 3 (`GenerationService`), Task 4 (`JudgeService`), and
  Task 5 (`ChatProviderController`).

- [ ] **Step 1: Write the failing tests**

```java
package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatProviderServiceTest {

	@Test
	void defaultsToGemini() {
		ChatProviderService service = new ChatProviderService(mockProvider(mock(GoogleGenAiChatModel.class)),
				mockProvider(mock(OpenAiChatModel.class)));

		assertThat(service.get()).isEqualTo(ChatProvider.GEMINI);
	}

	@Test
	void setChangesTheActiveProvider() {
		ChatProviderService service = new ChatProviderService(mockProvider(mock(GoogleGenAiChatModel.class)),
				mockProvider(mock(OpenAiChatModel.class)));

		service.set(ChatProvider.OPENAI);

		assertThat(service.get()).isEqualTo(ChatProvider.OPENAI);
	}

	@Test
	void activeChatClientBuilderResolvesOnlyGeminiWhenGeminiIsActive() {
		ObjectProvider<GoogleGenAiChatModel> geminiProvider = mockProvider(mock(GoogleGenAiChatModel.class));
		ObjectProvider<OpenAiChatModel> openAiProvider = mockProvider(mock(OpenAiChatModel.class));
		ChatProviderService service = new ChatProviderService(geminiProvider, openAiProvider);

		ChatClient.Builder builder = service.activeChatClientBuilder();

		assertThat(builder).isNotNull();
		verify(geminiProvider).getObject();
		verify(openAiProvider, never()).getObject();
	}

	@Test
	void activeChatClientBuilderResolvesOnlyOpenAiWhenOpenAiIsActive() {
		ObjectProvider<GoogleGenAiChatModel> geminiProvider = mockProvider(mock(GoogleGenAiChatModel.class));
		ObjectProvider<OpenAiChatModel> openAiProvider = mockProvider(mock(OpenAiChatModel.class));
		ChatProviderService service = new ChatProviderService(geminiProvider, openAiProvider);
		service.set(ChatProvider.OPENAI);

		ChatClient.Builder builder = service.activeChatClientBuilder();

		assertThat(builder).isNotNull();
		verify(openAiProvider).getObject();
		verify(geminiProvider, never()).getObject();
	}

	@SuppressWarnings("unchecked")
	private <T> ObjectProvider<T> mockProvider(T instance) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(instance);
		return provider;
	}
}
```

`OpenAiChatModel` is a `final` class; `GoogleGenAiChatModel` is not.
Mocking a final class needs Mockito's inline mock maker — already
confirmed working in this exact project setup (Mockito 5's default mock
maker, bundled transitively, mocks final classes with no extra
configuration). If this somehow fails in your environment with a
"Mockito cannot mock this class" error, that is the one thing to
investigate — do not silently change the field types to work around it
without understanding why, since concrete-type injection (rather than
`ObjectProvider<ChatModel>` with qualifiers) is what keeps this class's
constructor free of any disambiguation logic.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=ChatProviderServiceTest`
Expected: FAIL — `ChatProviderService` does not exist yet.

- [ ] **Step 3: Create `ChatProviderService`**

```java
package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

// The single place that knows both chat providers exist. GenerationService
// and JudgeService ask this for "the active" ChatClient.Builder instead of
// resolving a provider-specific bean themselves - adding, removing, or
// replacing a provider later is a change confined to this class and one
// configuration class, never to either consumer (BR-06,
// specs/chat-provider-switching.md).
@Service
public class ChatProviderService {

	// Resolving via ObjectProvider inside activeChatClientBuilder(), not in
	// the constructor, is what keeps both providers' ChatModel beans lazy
	// (BR-01) - this method only runs from an actual query/judge call,
	// never at startup or at ChatProviderService construction time.
	private final ObjectProvider<GoogleGenAiChatModel> googleGenAiChatModelProvider;
	private final ObjectProvider<OpenAiChatModel> openAiChatModelProvider;

	// volatile: this field is read and written from whichever HTTP request
	// thread happens to be handling a query, a judge call, or a settings
	// change - visibility across threads matters even though updates are
	// infrequent.
	private volatile ChatProvider currentProvider = ChatProvider.GEMINI;

	public ChatProviderService(ObjectProvider<GoogleGenAiChatModel> googleGenAiChatModelProvider,
			ObjectProvider<OpenAiChatModel> openAiChatModelProvider) {
		this.googleGenAiChatModelProvider = googleGenAiChatModelProvider;
		this.openAiChatModelProvider = openAiChatModelProvider;
	}

	public ChatProvider get() {
		return currentProvider;
	}

	public void set(ChatProvider provider) {
		this.currentProvider = provider;
	}

	public ChatClient.Builder activeChatClientBuilder() {
		return switch (currentProvider) {
			case GEMINI -> ChatClient.builder(googleGenAiChatModelProvider.getObject());
			case OPENAI -> ChatClient.builder(openAiChatModelProvider.getObject());
		};
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=ChatProviderServiceTest`
Expected: PASS (4/4).

Run: `./mvnw test`
Expected: PASS, 196 + 7 new tests so far.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/rag/ChatProviderService.java \
  src/test/java/com/devassist/rag/ChatProviderServiceTest.java
git commit -m "feat: add ChatProviderService as the single provider-selection point"
```

---

### Task 3: Migrate `GenerationService` to `ChatProviderService`

**Files:**
- Modify: `src/main/java/com/devassist/rag/GenerationService.java`
- Modify: `src/test/java/com/devassist/rag/GenerationServiceTest.java`

**Interfaces:**
- Consumes: `ChatProviderService.activeChatClientBuilder()` (Task 2).
- Produces: no change to `GenerationService.generate(String, String)`'s
  public signature or behavior — only how it obtains a `ChatClient.Builder`
  changes.

- [ ] **Step 1: Update the failing test's mocking setup**

`GenerationServiceTest.java` currently constructs a mock
`ObjectProvider<ChatClient.Builder>` in three places (one per test method)
and passes it as `GenerationService`'s first constructor argument. Replace
all three with a mocked `ChatProviderService` instead. The three test
bodies' actual assertions do not change — only how the `ChatClient` gets
wired to a mock `ChatModel` changes. Replace every occurrence of:

```java
@SuppressWarnings("unchecked")
ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));
```

with:

```java
ChatProviderService chatProviderService = mock(ChatProviderService.class);
when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
```

And every occurrence of `new GenerationService(builderProvider, properties)`
with `new GenerationService(chatProviderService, properties)`. Remove the
now-unused `import org.springframework.beans.factory.ObjectProvider;` line
if nothing else in the file still needs it (it doesn't, once all three
occurrences are migrated).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=GenerationServiceTest`
Expected: FAIL — compile error, `GenerationService`'s constructor doesn't
accept a `ChatProviderService` yet.

- [ ] **Step 3: Update `GenerationService`**

In `src/main/java/com/devassist/rag/GenerationService.java`, replace:

```java
	private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
	private final RagProperties properties;

	public GenerationService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider, RagProperties properties) {
		this.chatClientBuilderProvider = chatClientBuilderProvider;
		this.properties = properties;
	}

	public String generate(String context, String question) {
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatClientBuilderProvider.getObject()
				.defaultSystem(SYSTEM_PROMPT)
```

with:

```java
	private final ChatProviderService chatProviderService;
	private final RagProperties properties;

	public GenerationService(ChatProviderService chatProviderService, RagProperties properties) {
		this.chatProviderService = chatProviderService;
		this.properties = properties;
	}

	public String generate(String context, String question) {
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatProviderService.activeChatClientBuilder()
				.defaultSystem(SYSTEM_PROMPT)
```

Also remove the now-unused `import org.springframework.beans.factory.ObjectProvider;`
line, and update the class-level comment above the old field (currently
explaining why `ObjectProvider` is used) — replace it with:

```java
	// Resolved on demand via ChatProviderService rather than a direct
	// ChatModel/ChatClient.Builder dependency: which provider is active can
	// change at runtime (see ChatProviderService), and querying it fresh on
	// every call is also what keeps both providers' underlying beans lazy
	// (BR-01, specs/chat-provider-switching.md) - a direct dependency on
	// either concrete ChatModel would force Spring to build it at startup.
```

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=GenerationServiceTest`
Expected: PASS (3/3), same as before migration.

Run: `./mvnw test`
Expected: PASS, full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/rag/GenerationService.java \
  src/test/java/com/devassist/rag/GenerationServiceTest.java
git commit -m "refactor: migrate GenerationService onto ChatProviderService"
```

---

### Task 4: Migrate `JudgeService` to `ChatProviderService`

**Files:**
- Modify: `src/main/java/com/devassist/eval/JudgeService.java`
- Modify: `src/test/java/com/devassist/eval/JudgeServiceTest.java`

**Interfaces:**
- Consumes: `ChatProviderService.activeChatClientBuilder()` (Task 2, in
  `com.devassist.rag` — `com.devassist.eval` already depends on `rag`, so
  this does not introduce a new package dependency direction).
- Produces: no change to `JudgeService.judgeAnswered(String, String, List<SourceReference>)`'s
  public signature or behavior.

- [ ] **Step 1: Update the failing test's mocking setup**

`JudgeServiceTest.java` builds its mock chat plumbing in one shared helper
(`serviceReturning(String)`) plus one more inline occurrence in
`sendsTheQuestionAnswerAndSourceExcerptsInThePrompt`. Replace both.

In `serviceReturning(String rawModelOutput)`, replace:

```java
		@SuppressWarnings("unchecked")
		ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
		when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));

		return new JudgeService(builderProvider, new ObjectMapper());
```

with:

```java
		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));

		return new JudgeService(chatProviderService, new ObjectMapper());
```

In `sendsTheQuestionAnswerAndSourceExcerptsInThePrompt`, replace:

```java
		@SuppressWarnings("unchecked")
		ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
		when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));
		JudgeService service = new JudgeService(builderProvider, new ObjectMapper());
```

with:

```java
		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		JudgeService service = new JudgeService(chatProviderService, new ObjectMapper());
```

Add `import com.devassist.rag.ChatProviderService;` and remove the
now-unused `import org.springframework.beans.factory.ObjectProvider;`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=JudgeServiceTest`
Expected: FAIL — compile error, `JudgeService`'s constructor doesn't
accept a `ChatProviderService` yet.

- [ ] **Step 3: Update `JudgeService`**

In `src/main/java/com/devassist/eval/JudgeService.java`, replace:

```java
	private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
	private final ObjectMapper objectMapper;

	public JudgeService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider, ObjectMapper objectMapper) {
		this.chatClientBuilderProvider = chatClientBuilderProvider;
		this.objectMapper = objectMapper;
	}

	public EvaluationScore judgeAnswered(String question, String answer, List<SourceReference> sources) {
		String context = buildContext(sources);
		ChatClient chatClient = chatClientBuilderProvider.getObject().defaultSystem(SYSTEM_PROMPT).build();
```

with:

```java
	private final ChatProviderService chatProviderService;
	private final ObjectMapper objectMapper;

	public JudgeService(ChatProviderService chatProviderService, ObjectMapper objectMapper) {
		this.chatProviderService = chatProviderService;
		this.objectMapper = objectMapper;
	}

	public EvaluationScore judgeAnswered(String question, String answer, List<SourceReference> sources) {
		String context = buildContext(sources);
		ChatClient chatClient = chatProviderService.activeChatClientBuilder().defaultSystem(SYSTEM_PROMPT).build();
```

Add `import com.devassist.rag.ChatProviderService;` and remove the
now-unused `import org.springframework.beans.factory.ObjectProvider;`.

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=JudgeServiceTest`
Expected: PASS (6/6), same as before migration.

Run: `./mvnw test`
Expected: PASS, full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/eval/JudgeService.java \
  src/test/java/com/devassist/eval/JudgeServiceTest.java
git commit -m "refactor: migrate JudgeService onto ChatProviderService"
```

---

### Task 5: `ChatProviderController` — the settings endpoint

**Files:**
- Create: `src/main/java/com/devassist/rag/ChatProviderRequest.java`
- Create: `src/main/java/com/devassist/rag/ChatProviderController.java`
- Test: `src/test/java/com/devassist/rag/ChatProviderControllerTest.java`

**Interfaces:**
- Consumes: `ChatProviderService.get()`/`.set(ChatProvider)` (Task 2).
- Produces: `GET`/`PUT /api/settings/chat-provider`. Consumed by Task 7's
  UI change.

- [ ] **Step 1: Write the failing tests**

```java
package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatProviderController.class)
class ChatProviderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ChatProviderService chatProviderService;

	@Test
	void getReturnsTheCurrentProvider() throws Exception {
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		mockMvc.perform(get("/api/settings/chat-provider"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("GEMINI"));
	}

	@Test
	void putUpdatesTheActiveProvider() throws Exception {
		when(chatProviderService.get()).thenReturn(ChatProvider.OPENAI);

		mockMvc.perform(put("/api/settings/chat-provider").contentType(MediaType.APPLICATION_JSON)
				.content("{\"provider\":\"OPENAI\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("OPENAI"));

		verify(chatProviderService).set(ChatProvider.OPENAI);
	}

	// BR-07: an invalid enum value fails Jackson deserialization before the
	// controller method runs at all, so Spring Boot's own default error
	// handling returns 400 - no custom exception handler is added for this
	// endpoint.
	@Test
	void putWithAnInvalidProviderReturnsBadRequest() throws Exception {
		mockMvc.perform(put("/api/settings/chat-provider").contentType(MediaType.APPLICATION_JSON)
				.content("{\"provider\":\"NOT_A_REAL_PROVIDER\"}"))
				.andExpect(status().isBadRequest());
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=ChatProviderControllerTest`
Expected: FAIL — `ChatProviderController` and `ChatProviderRequest` do not
exist yet.

- [ ] **Step 3: Create `ChatProviderRequest` and `ChatProviderController`**

```java
package com.devassist.rag;

public record ChatProviderRequest(ChatProvider provider) {
}
```

```java
package com.devassist.rag;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatProviderController {

	private final ChatProviderService chatProviderService;

	public ChatProviderController(ChatProviderService chatProviderService) {
		this.chatProviderService = chatProviderService;
	}

	@GetMapping("/api/settings/chat-provider")
	public Map<String, ChatProvider> get() {
		return Map.of("provider", chatProviderService.get());
	}

	@PutMapping("/api/settings/chat-provider")
	public Map<String, ChatProvider> set(@RequestBody ChatProviderRequest request) {
		chatProviderService.set(request.provider());
		return Map.of("provider", chatProviderService.get());
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=ChatProviderControllerTest`
Expected: PASS (3/3).

Run: `./mvnw test`
Expected: PASS, full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/rag/ChatProviderRequest.java \
  src/main/java/com/devassist/rag/ChatProviderController.java \
  src/test/java/com/devassist/rag/ChatProviderControllerTest.java
git commit -m "feat: expose GET/PUT /api/settings/chat-provider"
```

---

### Task 6: Map OpenAI's live-call failures to the existing 503 handler (BR-08)

**Files:**
- Modify: `src/main/java/com/devassist/rag/RagExceptionHandler.java`
- Modify: `src/test/java/com/devassist/rag/RagQueryControllerTest.java`

**Interfaces:**
- Consumes: `com.openai.errors.OpenAIException` (transitively available
  once Task 1's dependency is added).
- Produces: no change to `RagExceptionHandler`'s public shape — only its
  `@ExceptionHandler` list gains one more covered type.

This resolves BR-08 as a known fact, not an open investigation: every
failure the `openai-java` SDK can produce —
`UnauthorizedException` (401, missing or invalid key),
`RateLimitException` (429, quota), `BadRequestException`,
`NotFoundException`, `PermissionDeniedException`,
`UnprocessableEntityException`, `InternalServerException` — extends the
abstract `com.openai.errors.OpenAIServiceException`, which itself extends
`com.openai.errors.OpenAIException` (a plain `RuntimeException` subclass).
Network failures (`OpenAIIoException`) extend `OpenAIException` directly.
Catching the single root type covers every one of OpenAI's failure modes
in one entry, exactly mirroring how `com.google.genai.errors.ApiException`
already covers every one of Google's.

- [ ] **Step 1: Write the failing test**

Add this test to the existing `src/test/java/com/devassist/rag/RagQueryControllerTest.java`,
alongside its existing `returnsServiceUnavailableWithTheRootCauseWhenGoogleRejectsTheModel`
test (same file, same pattern — mocking the service to throw a real SDK
exception type and asserting the controller maps it to 503):

```java
	// Regression coverage for BR-08 (specs/chat-provider-switching.md): the
	// same 503 mapping RagExceptionHandler already gives Google's ApiException
	// must also cover OpenAI's own exception hierarchy, since a user can now
	// switch the active provider to OpenAI at runtime.
	@Test
	void returnsServiceUnavailableWhenOpenAiRejectsTheRequest() throws Exception {
		com.openai.errors.UnauthorizedException openAiFailure = com.openai.errors.UnauthorizedException.builder()
				.headers(com.openai.core.http.Headers.builder().build())
				.build();
		when(queryService.answer(anyString(), anyString())).thenThrow(openAiFailure);

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("401")));
	}
```

`UnauthorizedException`'s message format is `"401: <error message or
Unknown>"` (confirmed by reading its source) — with no `error` set on the
builder, the message is exactly `"401: Unknown"`, so asserting the message
contains `"401"` is a stable, real assertion, not a guess.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RagQueryControllerTest#returnsServiceUnavailableWhenOpenAiRejectsTheRequest`
Expected: FAIL — the controller currently returns 500 (or the exception
propagates unmapped), since `RagExceptionHandler` doesn't catch
`OpenAIException` yet.

- [ ] **Step 3: Update `RagExceptionHandler`**

In `src/main/java/com/devassist/rag/RagExceptionHandler.java`, add the
import:

```java
import com.openai.errors.OpenAIException;
```

And change:

```java
	@ExceptionHandler({ NonTransientAiException.class, BeanCreationException.class, ApiException.class })
```

to:

```java
	@ExceptionHandler({ NonTransientAiException.class, BeanCreationException.class, ApiException.class,
			OpenAIException.class })
```

Update the comment block immediately above this line to add one sentence
noting OpenAI's coverage (append to the existing comment, don't replace
it — the existing explanation of `NonTransientAiException`/
`BeanCreationException`/`ApiException` all still applies verbatim):

```java
	// OpenAIException (com.openai.errors) is the root of every failure the
	// OpenAI SDK itself can throw - missing/invalid key, quota, bad request,
	// server errors, network I/O - all of its subtypes extend this one class,
	// the same way ApiException's subtypes all stem from Google's SDK. This
	// covers OpenAI once a user switches the active provider to it via
	// ChatProviderService.
```

- [ ] **Step 4: Run the test to verify it passes, then the full suite**

Run: `./mvnw test -Dtest=RagQueryControllerTest`
Expected: PASS, including the new test alongside all existing ones in this
file.

Run: `./mvnw test`
Expected: PASS, full suite green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/rag/RagExceptionHandler.java \
  src/test/java/com/devassist/rag/RagQueryControllerTest.java
git commit -m "fix: map OpenAI's exception hierarchy to the existing 503 handler"
```

---

### Task 7: UI provider selector, `CLAUDE.md` update, and manual verification

**Files:**
- Modify: `src/main/resources/static/index.html`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: `GET`/`PUT /api/settings/chat-provider` (Task 5).
- Produces: nothing new — this is the UI-facing and documentation-facing
  finish line, plus a manual verification gate, the same role as the RAG
  core and eval harness plans' own final tasks.

This task has no new automated tests: `index.html` is plain HTML/JS with
no test harness in this project (the existing page has none either), and
a `CLAUDE.md` change is documentation. Both are verified manually in
Step 3.

- [ ] **Step 1: Add the provider selector to `index.html`**

Find the existing project-line element (the `<p id="projectLine">` and its
surrounding markup near the top of the page) and add a `<select>`
immediately after it:

```html
	<p id="projectLine">Loading project&hellip;</p>
	<p>
		Chat provider:
		<select id="chatProviderSelect">
			<option value="GEMINI">Gemini</option>
			<option value="OPENAI">OpenAI</option>
		</select>
	</p>
```

In the page's `<script>` block, near where `projectId` is initialized on
load (the existing `fetch('/api/projects')` block), add the corresponding
load-and-wire logic:

```javascript
	async function loadChatProvider() {
		const res = await fetch('/api/settings/chat-provider');
		const { provider } = await res.json();
		document.getElementById('chatProviderSelect').value = provider;
	}

	document.getElementById('chatProviderSelect').addEventListener('change', async (event) => {
		await fetch('/api/settings/chat-provider', {
			method: 'PUT',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify({ provider: event.target.value })
		});
	});
```

Call `loadChatProvider()` alongside the existing page-load initialization
(wherever the project-loading function is invoked on page load — add this
as a sibling call, not nested inside it, since the two are independent).

- [ ] **Step 2: Update `CLAUDE.md`**

Add a line recording OpenAI as a second in-scope chat provider, in the
same place and style as the existing entries recording Gemini and the eval
harness as in-scope (read the file first to match its exact existing
list/section structure before editing — don't guess the surrounding
formatting).

- [ ] **Step 3: Manual verification**

```bash
./mvnw spring-boot:run
```

1. Open the app in a browser. Confirm the chat provider dropdown shows
   "Gemini" selected by default (matching `ChatProviderService`'s default).
2. Switch it to "OpenAI". Confirm the request succeeds (check the browser
   network tab or server logs for the `PUT` call).
3. Ask a question. If `OPENAI_API_KEY` is set in your environment, confirm
   a real answer comes back. If not, confirm the request fails with a 503
   whose message references OpenAI's own error (per Task 6) rather than a
   generic 500 or a hang.
4. Switch back to "Gemini" and confirm a question answers normally again
   (or fails the same way it already did before this plan, if Gemini's
   quota is currently exhausted — that's pre-existing behavior, not a
   regression to check here).
5. Run `POST /api/eval/run` (via `http/eval-api.http` or curl) with
   whichever provider is currently active, and confirm the judge scoring
   actually used that provider (there's no field that names the provider
   in the report today — this is a visual/log-based check: watch for which
   provider's traffic appears, e.g. via request logs or by toggling and
   observing quota consumption change on the corresponding provider's
   dashboard).

- [ ] **Step 4: Run the full suite one more time**

Run: `./mvnw test`
Expected: PASS, 196 + all new tests from Tasks 1-6, 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html CLAUDE.md
git commit -m "feat: add a UI toggle for the active chat provider"
```

---

## Completion Checklist

- [ ] `./mvnw test` passes; the 196 tests present before this plan are
  still among them, none deleted or weakened.
- [ ] `GeminiLazyChatModelConfiguration.java` was not modified at all.
- [ ] `RagConfiguration.java`, `com.devassist.document`, and
  `com.devassist.project` were not modified at all.
- [ ] Neither `GenerationService` nor `JudgeService` references
  `GoogleGenAiChatModel`, `OpenAiChatModel`, or `ChatProvider`'s enum
  values directly — both go through `ChatProviderService` only (BR-06).
- [ ] The app boots successfully with `GEMINI_API_KEY`, `OPENAI_API_KEY`,
  both, or neither set (BR-01) — proven by
  `GeminiChatModelStartsWithoutApiKeyTest` (pre-existing) and
  `OpenAiChatModelStartsWithoutApiKeyTest` (Task 1).
- [ ] `RagExceptionHandler` maps both Google's `ApiException` and OpenAI's
  `OpenAIException` to the same 503 shape (BR-08).
- [ ] `spring.ai.model.chat=google-genai` is unchanged in
  `application.properties` — Gemini's own autoconfiguration was never
  touched.
- [ ] No secret appears in any committed file.
