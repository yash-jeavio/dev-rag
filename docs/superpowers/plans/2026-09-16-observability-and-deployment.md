# API Observability, Error-Response Consistency, and Docker Deployment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Micrometer/Actuator observability to the RAG query and summarization pipelines, unify all four exception handlers onto one typed error-response shape, and make the whole application deployable with a single `docker compose up`.

**Architecture:** Three independent parts touching disjoint files. Part A injects a `MeterRegistry` into the three services that do retrieval/generation work, recording timers and counters without changing any public method signature. Part B introduces one new shared `com.devassist.common.ErrorResponse` record that all four existing `@RestControllerAdvice` classes switch to, closing the "no catch-all handler" gap along the way. Part C adds a multi-stage `Dockerfile` and a `docker-compose.yml` that also runs Ollama, with an init service that pulls the required embedding model automatically.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring AI 2.0.1, Micrometer 1.17.1 (via `spring-boot-starter-actuator`), Docker Compose.

**Spec:** `specs/observability-and-deployment.md`

## Global Constraints

- Java 17 / Spring Boot 4.1.1 are fixed — no framework changes.
- Maven Wrapper (`./mvnw`) only, including inside the Dockerfile's build stage.
- The only new dependency is `spring-boot-starter-actuator`, version controlled by the parent BOM (no explicit `<version>`).
- `GenerationService.generate(String, String) -> String` and `SummaryGenerator.summarize(String, String) -> String` keep their exact public signatures — Part A is internal-only, no caller anywhere needs to change.
- Every existing exception handler's JSON response shape must stay byte-identical (BR-06) — this is a refactor of *how* the response is built, not a change to *what* is produced. No existing `@WebMvcTest`/`jsonPath(...)` assertion in `DocumentControllerTest`, `ProjectControllerTest`, `RagQueryControllerTest`, `IndexStatusControllerTest`, `SummarizationControllerTest`, or `EvalControllerTest` should need to change.
- No API key is ever baked into the Docker image — both `GEMINI_API_KEY`/`OPENAI_API_KEY` arrive only via a gitignored `.env` file at `docker compose up` time.
- No change to authentication/authorization, PGVector, or retrieval/generation *behavior* — this plan is observability, response-shape consistency, and packaging only.
- Constructor injection and immutable records throughout — no deviation from existing conventions.
- Run `./mvnw test` after each task; the baseline going into this plan is **218 tests, 0 failures, 0 errors**.

---

### Task 1: Observability for `RetrievalService`

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/devassist/rag/RetrievalService.java`
- Modify: `src/test/java/com/devassist/rag/RetrievalServiceTest.java`

**Interfaces:**
- Consumes: nothing from another task.
- Produces: `RetrievalService`'s constructor now takes `(VectorStore, RagProperties, MeterRegistry)` — Task 2 does not depend on this, but the `spring-boot-starter-actuator` dependency this task adds is required by Task 2 too.

`RetrievalService` has exactly one production caller (Spring's own dependency injection when constructing `RagQueryService`'s dependencies — nothing in the codebase calls `new RetrievalService(...)` directly outside tests), so its constructor signature changes outright rather than gaining an overload, the same precedent set when `documentIds` was added to it.

- [ ] **Step 1: Add the Actuator dependency and enable the metrics endpoint**

In `pom.xml`, add this dependency inside the existing `<dependencies>` block (anywhere among the other `spring-boot-starter-*` entries — no explicit `<version>`, the parent BOM controls it):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
```

In `src/main/resources/application.properties`, add this line (anywhere — e.g. after the existing `devassist.rag.*` block):

```properties
management.endpoints.web.exposure.include=metrics,health
```

- [ ] **Step 2: Run the full suite to confirm the new dependency doesn't break anything**

Run: `./mvnw test`
Expected: PASS, 218/218 (no test yet depends on `MeterRegistry` — this step only confirms the dependency addition itself is safe).

- [ ] **Step 3: Write the failing test for the retrieval duration timer**

In `src/test/java/com/devassist/rag/RetrievalServiceTest.java`, the current file is:

```java
package com.devassist.rag;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

	private VectorStore vectorStore;
	private RetrievalService service;

	@BeforeEach
	void setUp() {
		vectorStore = mock(VectorStore.class);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
		service = new RetrievalService(vectorStore, new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000));
	}

	// ... 6 existing @Test methods, each calling service.retrieve(...) or
	// constructing "RetrievalService otherService = new RetrievalService(vectorStore,
	// new RagProperties(3, 0.9, 2000, 200, 0.1, 300, 200000));" in
	// topKAndThresholdAreNotHardcodedButReadFromProperties
}
```

Replace the whole file with this (adds a `MeterRegistry meterRegistry` field constructed in `setUp()`, threads it into both constructor call sites, and adds one new test at the end — every existing test method and its assertions are otherwise untouched):

```java
package com.devassist.rag;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

	private VectorStore vectorStore;
	private MeterRegistry meterRegistry;
	private RetrievalService service;

	@BeforeEach
	void setUp() {
		vectorStore = mock(VectorStore.class);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
		meterRegistry = new SimpleMeterRegistry();
		service = new RetrievalService(vectorStore, new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000),
				meterRegistry);
	}

	@Test
	void alwaysFiltersByProjectId() {
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getFilterExpression()).isNotNull();
		assertThat(captor.getValue().getFilterExpression().toString()).contains("proj-1");
	}

	@Test
	void appliesConfiguredTopKAndThreshold() {
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getTopK()).isEqualTo(5);
		assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.5);
	}

	@Test
	void returnsEmptyListWhenStoreReturnsNull() {
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

		assertThat(service.retrieve("proj-1", "q", List.of())).isEmpty();
	}

	@Test
	void filterIsScopedToTheProjectIdMetadataKeyNotSomeOtherKey() {
		// Guards against a filter built on the wrong metadata key (e.g.
		// "documentId") that would still happen to contain the projectId value.
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getFilterExpression().toString()).contains("projectId");
	}

	@Test
	void topKAndThresholdAreNotHardcodedButReadFromProperties() {
		// Different configured values than the setUp() instance, so a hardcoded
		// implementation matching the first test's numbers by coincidence
		// would fail here.
		RetrievalService otherService = new RetrievalService(vectorStore,
				new RagProperties(3, 0.9, 2000, 200, 0.1, 300, 200000), meterRegistry);

		otherService.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getTopK()).isEqualTo(3);
		assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.9);
	}

	@Test
	void queryTextIsPassedThroughToTheRequest() {
		service.retrieve("proj-1", "what is the deployment process?", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getQuery()).isEqualTo("what is the deployment process?");
	}

	@Test
	void narrowsToTheGivenDocumentsWhenDocumentIdsIsNonEmpty() {
		service.retrieve("proj-1", "any question", List.of("doc-1", "doc-2"));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		Filter.Expression expression = captor.getValue().getFilterExpression();

		assertThat(expression.type()).isEqualTo(Filter.ExpressionType.AND);

		Filter.Expression projectClause = (Filter.Expression) expression.left();
		assertThat(projectClause.type()).isEqualTo(Filter.ExpressionType.EQ);
		assertThat(((Filter.Key) projectClause.left()).key()).isEqualTo("projectId");
		assertThat(((Filter.Value) projectClause.right()).value()).isEqualTo("proj-1");

		Filter.Expression documentClause = (Filter.Expression) expression.right();
		assertThat(documentClause.type()).isEqualTo(Filter.ExpressionType.IN);
		assertThat(((Filter.Key) documentClause.left()).key()).isEqualTo("documentId");
		assertThat(((Filter.Value) documentClause.right()).value()).isEqualTo(List.of("doc-1", "doc-2"));
	}

	@Test
	void anEmptyDocumentIdsListProducesExactlyTheSameFilterAsBefore() {
		// Guards against the compound-filter code path accidentally
		// changing the whole-project case's filter shape too.
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		String filter = captor.getValue().getFilterExpression().toString();
		assertThat(filter).contains("proj-1");
		assertThat(filter).doesNotContain("documentId");
	}

	@Test
	void recordsARetrievalDurationTimer() {
		service.retrieve("proj-1", "any question", List.of());

		Timer timer = meterRegistry.find("rag.retrieval.duration").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RetrievalServiceTest`
Expected: FAIL — compile error, `RetrievalService` has no 3-arg constructor yet.

- [ ] **Step 3: Instrument `RetrievalService`**

Replace `src/main/java/com/devassist/rag/RetrievalService.java` with:

```java
package com.devassist.rag;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Implements BR-06 and BR-07 (specs/rag-core.md): retrieval is always
 * scoped to the requested project and bounded to the configured top-k and
 * similarity threshold. Also implements BR-01/BR-02
 * (specs/document-scoped-retrieval.md): when documentIds is non-empty,
 * retrieval additionally narrows to just those documents - within the
 * project, never across it, since the projectId clause is always ANDed in.
 */
@Service
public class RetrievalService {

	private final VectorStore vectorStore;
	private final RagProperties properties;
	private final MeterRegistry meterRegistry;

	public RetrievalService(VectorStore vectorStore, RagProperties properties, MeterRegistry meterRegistry) {
		this.vectorStore = vectorStore;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	public List<Document> retrieve(String projectId, String question, List<String> documentIds) {
		SearchRequest request = SearchRequest.builder()
				.query(question)
				.topK(properties.topK())
				.similarityThreshold(properties.similarityThreshold())
				.filterExpression(buildFilter(projectId, documentIds))
				.build();

		Timer.Sample sample = Timer.start(meterRegistry);
		List<Document> results = vectorStore.similaritySearch(request);
		sample.stop(meterRegistry.timer("rag.retrieval.duration"));
		return (results != null) ? results : List.of();
	}

	// BR-06: built with FilterExpressionBuilder rather than string
	// concatenation, so a projectId or documentId value can never be
	// interpreted as filter syntax.
	private Filter.Expression buildFilter(String projectId, List<String> documentIds) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		if (documentIds.isEmpty()) {
			return builder.eq("projectId", projectId).build();
		}
		// FilterExpressionBuilder.in(String, List<Object>) does not accept
		// List<String> directly (Java generics are invariant) - the varargs
		// overload in(String, Object...) does, and List.toArray() already
		// returns Object[].
		return builder.and(builder.eq("projectId", projectId), builder.in("documentId", documentIds.toArray()))
				.build();
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=RetrievalServiceTest`
Expected: PASS (8/8).

Run: `./mvnw test`
Expected: PASS, 219 tests, 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/resources/application.properties \
  src/main/java/com/devassist/rag/RetrievalService.java \
  src/test/java/com/devassist/rag/RetrievalServiceTest.java
git commit -m "feat: add spring-boot-starter-actuator and instrument RetrievalService"
```

---

### Task 2: Observability for `GenerationService` and `SummaryGenerator`

**Files:**
- Modify: `src/main/java/com/devassist/rag/GenerationService.java`
- Modify: `src/main/java/com/devassist/rag/SummaryGenerator.java`
- Modify: `src/test/java/com/devassist/rag/GenerationServiceTest.java`
- Modify: `src/test/java/com/devassist/rag/SummaryGeneratorTest.java`

**Interfaces:**
- Consumes: `spring-boot-starter-actuator` (Task 1) so `MeterRegistry` is an available bean type; `ChatProvider` enum (already exists, `com.devassist.rag.ChatProvider`, values `GEMINI`/`OPENAI`) and `ChatProviderService.get()` (already exists, returns `ChatProvider`) for the provider tag.
- Produces: nothing later tasks depend on — `generate(String, String) -> String` and `summarize(String, String) -> String` are unchanged, so `RagQueryService` and `SummarizationService` (their only callers) need zero changes.

Confirmed directly against the real Spring AI 2.0.1 jars on this classpath: `ChatClient.CallResponseSpec.chatResponse()` returns `org.springframework.ai.chat.model.ChatResponse`; `ChatResponse.getResult().getOutput().getText()` returns the answer string (same text `.call().content()` already returns internally); `ChatResponse.getMetadata().getUsage()` returns a non-null `Usage` (Spring AI defaults to `EmptyUsage`, which returns `0` from every getter, never `null` — so no null-check is needed around `getUsage()` itself or its token-count getters). `Usage.getPromptTokens()`/`getCompletionTokens()` return `Integer`, auto-unboxed and widened to `double` when passed to `Counter.increment(double)`.

- [ ] **Step 1: Write the failing tests**

In `src/test/java/com/devassist/rag/GenerationServiceTest.java`, the current file's three existing tests each construct `new GenerationService(chatProviderService, properties)` (2-arg). Replace the whole file with:

```java
package com.devassist.rag;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationServiceTest {

	@Test
	void sendsAFixedSystemPromptAndReturnsTheModelsAnswer() {
		ChatModel chatModel = mock(ChatModel.class);
		// The real ChatClient merges request options with the model's defaults,
		// so a mock must return real ChatOptions rather than Mockito's null default.
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Within 30 days. [1]")))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, new SimpleMeterRegistry());
		String answer = service.generate("[1] refund text", "refund window?");

		assertThat(answer).isEqualTo("Within 30 days. [1]");

		// BR-09: the refusal wording must be the fixed, exact phrase a later
		// evaluator matches on, not paraphrased or reworded in the prompt.
		String promptText = promptCaptor.getValue().getContents();
		assertThat(promptText).contains("I don't have enough information to answer this.");
		assertThat(promptText).contains("ONLY the provided context");
		assertThat(promptText).contains("[1] refund text");
		assertThat(promptText).contains("refund window?");
	}

	// Guards against the two literals (here and in RagQueryService) drifting
	// apart again now that a shared constant exists.
	@Test
	void systemPromptEmbedsTheSharedRefusalConstant() {
		assertThat(GenerationService.SYSTEM_PROMPT).contains(RagQueryService.NO_CONTEXT_ANSWER);
	}

	// Fix 1: devassist.rag.temperature was configured and bound but never
	// actually passed to the ChatClient, so generation silently ran at the
	// provider's default (~1.0) instead of the low, factual-grounding value
	// BR-09 calls for. This asserts the configured value reaches the actual
	// Prompt sent to the model, not just that the property binds.
	@Test
	void appliesTheConfiguredTemperatureToTheChatClient() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.42, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, new SimpleMeterRegistry());
		service.generate("context", "question");

		assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.42);
	}

	@Test
	void recordsGenerationDurationAndTokenUsageTaggedByProvider() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
				List.of(new Generation(new AssistantMessage("answer"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(42, 17)).build()));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, meterRegistry);

		service.generate("context", "question");

		Timer timer = meterRegistry.find("rag.generation.duration").tag("provider", "gemini").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);

		Counter promptTokens = meterRegistry.find("rag.generation.tokens")
				.tag("provider", "gemini")
				.tag("type", "prompt")
				.counter();
		assertThat(promptTokens).isNotNull();
		assertThat(promptTokens.count()).isEqualTo(42);

		Counter completionTokens = meterRegistry.find("rag.generation.tokens")
				.tag("provider", "gemini")
				.tag("type", "completion")
				.counter();
		assertThat(completionTokens).isNotNull();
		assertThat(completionTokens.count()).isEqualTo(17);
	}
}
```

In `src/test/java/com/devassist/rag/SummaryGeneratorTest.java`, replace the whole file with:

```java
package com.devassist.rag;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SummaryGeneratorTest {

	@Test
	void sendsTheDocumentAndInstructionAndReturnsTheModelsSummary() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("A short summary.")))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(chatProviderService, properties, new SimpleMeterRegistry());
		String summary = generator.summarize("document body", "Focus on timeframes.");

		assertThat(summary).isEqualTo("A short summary.");
		String promptText = promptCaptor.getValue().getContents();
		assertThat(promptText).contains("document body");
		assertThat(promptText).contains("Focus on timeframes.");
	}

	// Fix 1: devassist.rag.temperature was configured and bound but never
	// actually passed to the ChatClient here either, so summarisation also ran
	// at the provider's default (~1.0) instead of BR-09's low, factual value.
	@Test
	void appliesTheConfiguredTemperatureToTheChatClient() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.33, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(chatProviderService, properties, new SimpleMeterRegistry());
		generator.summarize("document body", null);

		assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.33);
	}

	@Test
	void recordsSummarizationDurationAndTokenUsageTaggedByProvider() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
				List.of(new Generation(new AssistantMessage("summary"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(30, 10)).build()));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.OPENAI);

		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(chatProviderService, properties, meterRegistry);

		generator.summarize("document body", null);

		Timer timer = meterRegistry.find("rag.summarization.duration").tag("provider", "openai").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);

		Counter promptTokens = meterRegistry.find("rag.summarization.tokens")
				.tag("provider", "openai")
				.tag("type", "prompt")
				.counter();
		assertThat(promptTokens).isNotNull();
		assertThat(promptTokens.count()).isEqualTo(30);

		Counter completionTokens = meterRegistry.find("rag.summarization.tokens")
				.tag("provider", "openai")
				.tag("type", "completion")
				.counter();
		assertThat(completionTokens).isNotNull();
		assertThat(completionTokens.count()).isEqualTo(10);
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=GenerationServiceTest,SummaryGeneratorTest`
Expected: FAIL — compile errors, neither class has a 3-arg constructor yet, and `ChatProviderService.get()` is not yet stubbed to matter (it will be, once the implementation calls it).

- [ ] **Step 3: Instrument `GenerationService`**

Replace `src/main/java/com/devassist/rag/GenerationService.java` with:

```java
package com.devassist.rag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

@Service
public class GenerationService {

	// BR-09: the "I don't have enough information" wording is fixed so that
	// sub-project 3 can detect refusals without parsing prose. Interpolated
	// from RagQueryService.NO_CONTEXT_ANSWER (the single definition) instead
	// of being retyped here, so the two call sites cannot drift apart.
	static final String SYSTEM_PROMPT = """
			You are a careful assistant answering questions about a user's documents.

			Rules:
			1. Answer using ONLY the provided context. Never use outside knowledge.
			2. If the context does not contain enough information, reply with exactly:
			   %s
			3. Cite the context blocks you used with markers like [1] or [2].
			""".formatted(RagQueryService.NO_CONTEXT_ANSWER);

	// Resolved on demand via ChatProviderService rather than a direct
	// ChatModel/ChatClient.Builder dependency: which provider is active can
	// change at runtime (see ChatProviderService), and querying it fresh on
	// every call is also what keeps both providers' underlying beans lazy
	// (BR-01, specs/chat-provider-switching.md) - a direct dependency on
	// either concrete ChatModel would force Spring to build it at startup.
	private final ChatProviderService chatProviderService;
	private final RagProperties properties;
	private final MeterRegistry meterRegistry;

	public GenerationService(ChatProviderService chatProviderService, RagProperties properties,
			MeterRegistry meterRegistry) {
		this.chatProviderService = chatProviderService;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	public String generate(String context, String question) {
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatProviderService.activeChatClientBuilder()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultOptions(ChatOptions.builder().temperature(properties.temperature()))
				.build();

		String provider = chatProviderService.get().name().toLowerCase();
		Timer.Sample sample = Timer.start(meterRegistry);
		ChatResponse response = chatClient.prompt()
				.user("Context:\n%s\n\nQuestion: %s".formatted(context, question))
				.call()
				.chatResponse();
		sample.stop(meterRegistry.timer("rag.generation.duration", "provider", provider));

		Usage usage = response.getMetadata().getUsage();
		meterRegistry.counter("rag.generation.tokens", "provider", provider, "type", "prompt")
				.increment(usage.getPromptTokens());
		meterRegistry.counter("rag.generation.tokens", "provider", provider, "type", "completion")
				.increment(usage.getCompletionTokens());

		return response.getResult().getOutput().getText();
	}
}
```

- [ ] **Step 4: Instrument `SummaryGenerator`**

Replace `src/main/java/com/devassist/rag/SummaryGenerator.java` with:

```java
package com.devassist.rag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

@Service
public class SummaryGenerator {

	private static final String SYSTEM_PROMPT = """
			You summarise documents accurately and concisely.
			Use only the supplied text. Do not invent details.
			If the text appears truncated, summarise what is present without speculating about the rest.
			""";

	// Resolved on demand via ChatProviderService rather than a direct
	// ChatModel/ChatClient.Builder dependency: which provider is active can
	// change at runtime (see ChatProviderService), and querying it fresh on
	// every call is also what keeps both providers' underlying beans lazy -
	// a direct dependency on either concrete ChatModel would force Spring to
	// build it at startup.
	private final ChatProviderService chatProviderService;
	private final RagProperties properties;
	private final MeterRegistry meterRegistry;

	public SummaryGenerator(ChatProviderService chatProviderService, RagProperties properties,
			MeterRegistry meterRegistry) {
		this.chatProviderService = chatProviderService;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	public String summarize(String text, String instruction) {
		String steer = (instruction == null || instruction.isBlank())
				? "Summarise the document."
				: instruction;
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatProviderService.activeChatClientBuilder()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultOptions(ChatOptions.builder().temperature(properties.temperature()))
				.build();

		String provider = chatProviderService.get().name().toLowerCase();
		Timer.Sample sample = Timer.start(meterRegistry);
		ChatResponse response = chatClient.prompt()
				.user("%s\n\nDocument:\n%s".formatted(steer, text))
				.call()
				.chatResponse();
		sample.stop(meterRegistry.timer("rag.summarization.duration", "provider", provider));

		Usage usage = response.getMetadata().getUsage();
		meterRegistry.counter("rag.summarization.tokens", "provider", provider, "type", "prompt")
				.increment(usage.getPromptTokens());
		meterRegistry.counter("rag.summarization.tokens", "provider", provider, "type", "completion")
				.increment(usage.getCompletionTokens());

		return response.getResult().getOutput().getText();
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=GenerationServiceTest,SummaryGeneratorTest`
Expected: PASS (4/4 and 3/3).

Run: `./mvnw test`
Expected: PASS, 221 tests, 0 failures, 0 errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/rag/GenerationService.java \
  src/main/java/com/devassist/rag/SummaryGenerator.java \
  src/test/java/com/devassist/rag/GenerationServiceTest.java \
  src/test/java/com/devassist/rag/SummaryGeneratorTest.java
git commit -m "feat: instrument GenerationService and SummaryGenerator with Micrometer metrics"
```

---

### Task 3: `ErrorResponse` and consistent exception handling across all four handlers

**Files:**
- Create: `src/main/java/com/devassist/common/ErrorResponse.java`
- Create: `src/test/java/com/devassist/common/ErrorResponseTest.java`
- Modify: `src/main/java/com/devassist/document/DocumentExceptionHandler.java`
- Modify: `src/main/java/com/devassist/project/ProjectExceptionHandler.java`
- Modify: `src/main/java/com/devassist/rag/RagExceptionHandler.java`
- Modify: `src/main/java/com/devassist/eval/EvalExceptionHandler.java`
- Create: `src/test/java/com/devassist/document/DocumentExceptionHandlerTest.java`
- Create: `src/test/java/com/devassist/project/ProjectExceptionHandlerTest.java`
- Create: `src/test/java/com/devassist/rag/RagExceptionHandlerTest.java`
- Modify: `src/test/java/com/devassist/eval/EvalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nothing from Tasks 1-2.
- Produces: nothing later tasks depend on.

`com.devassist.common` is a new, minimal package with exactly one file. It depends on nothing in `document`/`project`/`rag`/`eval`, so the existing one-way dependency rule (`eval` → `rag`, documented in `EvalExceptionHandler`'s own comment; no other cross-feature-package dependency exists) is unaffected — this new package sits below all four, not beside any of them.

None of the four `*ControllerTest` files (`DocumentControllerTest`, `ProjectControllerTest`, `RagQueryControllerTest`, `IndexStatusControllerTest`, `SummarizationControllerTest`, `EvalControllerTest`) are touched by this task — their existing `jsonPath(...)` assertions passing unmodified after this task is the proof of BR-06 (byte-identical JSON shape).

- [ ] **Step 1: Write the failing test for `ErrorResponse`**

Create `src/test/java/com/devassist/common/ErrorResponseTest.java`:

```java
package com.devassist.common;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ErrorResponseTest {

	@Test
	void ofBuildsAMessageOnlyResponseWithNoErrors() {
		ErrorResponse response = ErrorResponse.of(HttpStatus.NOT_FOUND, "project not found");

		assertThat(response.status()).isEqualTo(404);
		assertThat(response.message()).isEqualTo("project not found");
		assertThat(response.errors()).isNull();
	}

	@Test
	void validationFailureBuildsAnErrorsOnlyResponseWithNoMessage() {
		FieldError fieldError = new FieldError("object", "name", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = ErrorResponse.validationFailure(ex);

		assertThat(response.status()).isEqualTo(400);
		assertThat(response.message()).isNull();
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("name", "must not be blank"));
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ErrorResponseTest`
Expected: FAIL — compile error, `com.devassist.common.ErrorResponse` does not exist yet.

- [ ] **Step 3: Create `ErrorResponse`**

Create `src/main/java/com/devassist/common/ErrorResponse.java`:

```java
package com.devassist.common;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;

// @JsonInclude(NON_NULL) keeps the JSON shape byte-identical to every
// existing Map.of(...)-based handler response: a message-only response
// never serializes "errors": null, and vice versa (BR-06,
// specs/observability-and-deployment.md).
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

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ErrorResponseTest`
Expected: PASS (2/2).

- [ ] **Step 5: Write the failing tests for all four handlers**

Create `src/test/java/com/devassist/document/DocumentExceptionHandlerTest.java`:

```java
package com.devassist.document;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.devassist.common.ErrorResponse;
import com.devassist.project.ProjectNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentExceptionHandlerTest {

	private final DocumentExceptionHandler handler = new DocumentExceptionHandler();

	@Test
	void mapsValidationFailureToBadRequestWithFieldErrors() {
		FieldError fieldError = new FieldError("object", "title", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = handler.handleValidation(ex);

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("title", "must not be blank"));
	}

	@Test
	void mapsProjectNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleProjectNotFound(new ProjectNotFoundException("proj-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("proj-x");
	}

	@Test
	void mapsDocumentNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleDocumentNotFound(new DocumentNotFoundException("doc-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("doc-x");
	}

	@Test
	void mapsInvalidDocumentToBadRequestWithTheExceptionMessage() {
		ErrorResponse response = handler.handleInvalidDocument(new InvalidDocumentException("empty file"));

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.message()).isEqualTo("empty file");
	}

	@Test
	void mapsMaxUploadSizeExceededToBadRequestWithAFixedMessage() {
		ErrorResponse response = handler
				.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(10_000_000L));

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.message()).isEqualTo("Uploaded file exceeds the maximum allowed size");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
```

Create `src/test/java/com/devassist/project/ProjectExceptionHandlerTest.java`:

```java
package com.devassist.project;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.devassist.common.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectExceptionHandlerTest {

	private final ProjectExceptionHandler handler = new ProjectExceptionHandler();

	@Test
	void mapsValidationFailureToBadRequestWithFieldErrors() {
		FieldError fieldError = new FieldError("object", "name", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = handler.handleValidation(ex);

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("name", "must not be blank"));
	}

	@Test
	void mapsProjectNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleNotFound(new ProjectNotFoundException("proj-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("proj-x");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
```

Create `src/test/java/com/devassist/rag/RagExceptionHandlerTest.java`:

```java
package com.devassist.rag;

import java.util.List;

import com.google.genai.errors.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import com.devassist.common.ErrorResponse;
import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagExceptionHandlerTest {

	private final RagExceptionHandler handler = new RagExceptionHandler();

	@Test
	void mapsProjectNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleNotFound(new ProjectNotFoundException("proj-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("proj-x");
	}

	@Test
	void mapsDocumentNotFoundToNotFoundWithTheExceptionMessage() {
		ErrorResponse response = handler.handleNotFound(new DocumentNotFoundException("doc-x"));

		assertThat(response.status()).isEqualTo(HttpStatus.NOT_FOUND.value());
		assertThat(response.message()).contains("doc-x");
	}

	@Test
	void mapsValidationFailureToBadRequestWithFieldErrors() {
		FieldError fieldError = new FieldError("object", "question", "must not be blank");
		BindingResult bindingResult = mock(BindingResult.class);
		when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
		MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
		when(ex.getBindingResult()).thenReturn(bindingResult);

		ErrorResponse response = handler.handleValidation(ex);

		assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST.value());
		assertThat(response.errors()).containsExactly(new ErrorResponse.FieldError("question", "must not be blank"));
	}

	@Test
	void mapsAnAiProviderFailureToServiceUnavailableWithTheRootCauseMessage() {
		ApiException rootCause = new ApiException(404, "NOT_FOUND", "model retired");
		ErrorResponse response = handler.handleAiFailure(new NonTransientAiException("wrapper", rootCause));

		assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
		assertThat(response.message()).contains("model retired");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
```

Replace `src/test/java/com/devassist/eval/EvalExceptionHandlerTest.java` with:

```java
package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.devassist.common.ErrorResponse;

import static org.assertj.core.api.Assertions.assertThat;

class EvalExceptionHandlerTest {

	private final EvalExceptionHandler handler = new EvalExceptionHandler();

	@Test
	void mapsCorpusNotReadyToServiceUnavailableWithTheFixedMessage() {
		ErrorResponse response = handler.handleCorpusNotReady(new EvalCorpusNotReadyException());

		assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
		assertThat(response.message()).isEqualTo(
				"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet.");
	}

	@Test
	void mapsAnyOtherExceptionToInternalServerErrorWithAGenericMessage() {
		ErrorResponse response = handler.handleUnexpected(new RuntimeException("some internal detail"));

		assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
		assertThat(response.message()).isEqualTo("An unexpected error occurred");
		assertThat(response.message()).doesNotContain("some internal detail");
	}
}
```

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentExceptionHandlerTest,ProjectExceptionHandlerTest,RagExceptionHandlerTest,EvalExceptionHandlerTest`
Expected: FAIL — compile errors (`handleUnexpected` doesn't exist on any of the four handlers yet; the existing handler methods still return `Map<String,Object>`, not `ErrorResponse`).

- [ ] **Step 7: Update all four handlers**

Replace `src/main/java/com/devassist/document/DocumentExceptionHandler.java` with:

```java
package com.devassist.document;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.devassist.common.ErrorResponse;
import com.devassist.project.ProjectNotFoundException;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
		return ErrorResponse.validationFailure(ex);
	}

	@ExceptionHandler(ProjectNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleProjectNotFound(ProjectNotFoundException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(DocumentNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleDocumentNotFound(DocumentNotFoundException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(InvalidDocumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleInvalidDocument(InvalidDocumentException ex) {
		return ErrorResponse.of(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
		return ErrorResponse.of(HttpStatus.BAD_REQUEST, "Uploaded file exceeds the maximum allowed size");
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}
}
```

Replace `src/main/java/com/devassist/project/ProjectExceptionHandler.java` with:

```java
package com.devassist.project;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.common.ErrorResponse;

@RestControllerAdvice(assignableTypes = ProjectController.class)
public class ProjectExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
		return ErrorResponse.validationFailure(ex);
	}

	@ExceptionHandler(ProjectNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNotFound(ProjectNotFoundException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}
}
```

Replace `src/main/java/com/devassist/rag/RagExceptionHandler.java` with:

```java
package com.devassist.rag;

import com.google.genai.errors.ApiException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.common.ErrorResponse;
import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;
import com.openai.errors.OpenAIException;

@RestControllerAdvice(assignableTypes = { IndexStatusController.class, RagQueryController.class,
		SummarizationController.class })
public class RagExceptionHandler {

	@ExceptionHandler({ ProjectNotFoundException.class, DocumentNotFoundException.class })
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public ErrorResponse handleNotFound(RuntimeException ex) {
		return ErrorResponse.of(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
		return ErrorResponse.validationFailure(ex);
	}

	// A model or embedding provider being unreachable is not the caller's fault
	// and is not permanent, so it is 503 rather than 500. NonTransientAiException
	// covers a live call that fails (e.g. quota/auth rejected by the provider);
	// BeanCreationException covers the chat model bean itself never getting
	// built in the first place (e.g. GEMINI_API_KEY missing) - GenerationService
	// and SummaryGenerator resolve it lazily via ObjectProvider, so that failure
	// now surfaces here at call time instead of at application startup.
	// ApiException covers Google's own SDK rejecting a request (bad/retired
	// model name, quota, auth) - Spring AI does not normalise this into
	// NonTransientAiException, and it arrives wrapped in a generic RuntimeException,
	// which @ExceptionHandler still matches because Spring searches the whole
	// cause chain, not just the directly-thrown type. OpenAIException
	// (com.openai.errors) is the equivalent root for every failure the OpenAI
	// SDK itself can throw - missing/invalid key, quota, bad request, server
	// errors, network I/O - all of its subtypes extend this one class, covering
	// OpenAI once a user switches the active provider to it via ChatProviderService.
	@ExceptionHandler({ NonTransientAiException.class, BeanCreationException.class, ApiException.class,
			OpenAIException.class })
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public ErrorResponse handleAiFailure(RuntimeException ex) {
		return ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE, "AI provider unavailable: " + deepestMessage(ex));
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}

	// Spring passes the exception instance that was actually thrown, which may
	// be a generic wrapper rather than the specific cause that matched this
	// handler - walk to the root cause so the caller sees Google's actual
	// explanation (e.g. "model no longer available") instead of a generic
	// wrapper message like "Failed to generate content".
	private String deepestMessage(Throwable ex) {
		Throwable current = ex;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current.getMessage();
	}
}
```

Replace `src/main/java/com/devassist/eval/EvalExceptionHandler.java` with:

```java
package com.devassist.eval;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devassist.common.ErrorResponse;

// Scoped to EvalController only, and deliberately separate from
// com.devassist.rag.RagExceptionHandler: that class lives in the `rag`
// package, and adding a handler for an `eval`-package exception there
// would make `rag` depend on `eval` - backwards, per this feature's own
// one-way dependency rule (eval depends on rag, never the reverse).
@RestControllerAdvice(assignableTypes = EvalController.class)
public class EvalExceptionHandler {

	@ExceptionHandler(EvalCorpusNotReadyException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public ErrorResponse handleCorpusNotReady(EvalCorpusNotReadyException ex) {
		return ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public ErrorResponse handleUnexpected(Exception ex) {
		return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
	}
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=ErrorResponseTest,DocumentExceptionHandlerTest,ProjectExceptionHandlerTest,RagExceptionHandlerTest,EvalExceptionHandlerTest`
Expected: PASS (2 + 6 + 3 + 5 + 2 = 18 tests).

Run: `./mvnw test`
Expected: PASS, 238 tests, 0 failures, 0 errors (221 from Task 2, +2 `ErrorResponseTest`, +6 new `DocumentExceptionHandlerTest`, +3 new `ProjectExceptionHandlerTest`, +5 new `RagExceptionHandlerTest`, +1 new test in the existing `EvalExceptionHandlerTest` file). This step is also where BR-06 gets its real proof: confirm the full run includes `DocumentControllerTest`, `ProjectControllerTest`, `RagQueryControllerTest`, `IndexStatusControllerTest`, `SummarizationControllerTest`, and `EvalControllerTest` all still passing, untouched by this task, which is what shows the actual HTTP JSON responses are unchanged.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/devassist/common/ErrorResponse.java \
  src/test/java/com/devassist/common/ErrorResponseTest.java \
  src/main/java/com/devassist/document/DocumentExceptionHandler.java \
  src/main/java/com/devassist/project/ProjectExceptionHandler.java \
  src/main/java/com/devassist/rag/RagExceptionHandler.java \
  src/main/java/com/devassist/eval/EvalExceptionHandler.java \
  src/test/java/com/devassist/document/DocumentExceptionHandlerTest.java \
  src/test/java/com/devassist/project/ProjectExceptionHandlerTest.java \
  src/test/java/com/devassist/rag/RagExceptionHandlerTest.java \
  src/test/java/com/devassist/eval/EvalExceptionHandlerTest.java
git commit -m "feat: unify all exception handlers onto a typed ErrorResponse with a catch-all fallback"
```

---

### Task 4: Docker Compose deployment

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `docker-compose.yml`
- Create: `.env.example`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing from Tasks 1-3 (this task's files are independent of them).
- Produces: nothing — this is the last task.

No automated test — this project has no build-time Docker verification, and infra configuration isn't unit-testable the way application code is (the manual test UI's own task in `docs/superpowers/plans/2026-09-16-document-scoped-retrieval.md` already established this precedent for `index.html`). Verification is manual, per Step 4 below.

- [ ] **Step 1: Create the Dockerfile**

Create `Dockerfile` at the repository root:

```dockerfile
# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
RUN ./mvnw -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create `.dockerignore`**

Create `.dockerignore` at the repository root:

```
target/
.git/
.idea/
.claude/
docs/
specs/
http/
*.md
```

- [ ] **Step 3: Create `docker-compose.yml`**

Create `docker-compose.yml` at the repository root:

```yaml
services:
  ollama:
    image: ollama/ollama:latest
    volumes:
      - ollama_data:/root/.ollama
    ports:
      - "11434:11434"

  ollama-pull:
    image: ollama/ollama:latest
    depends_on:
      - ollama
    entrypoint: ["sh", "-c", "sleep 3 && OLLAMA_HOST=ollama:11434 ollama pull nomic-embed-text"]

  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - SPRING_AI_OLLAMA_BASE_URL=http://ollama:11434
      - GEMINI_API_KEY=${GEMINI_API_KEY}
      - OPENAI_API_KEY=${OPENAI_API_KEY}
    depends_on:
      ollama-pull:
        condition: service_completed_successfully

volumes:
  ollama_data:
```

- [ ] **Step 4: Create the `.env` template and update `.gitignore`**

Create `.env.example` at the repository root:

```
GEMINI_API_KEY=
OPENAI_API_KEY=
```

In `.gitignore`, add this line (anywhere — e.g. at the end of the file):

```
.env
```

- [ ] **Step 5: Manual verification**

```bash
cp .env.example .env
```

Fill in a real `GEMINI_API_KEY` (or `OPENAI_API_KEY`) in `.env`.

```bash
docker compose up
```

1. Confirm the `ollama-pull` service completes and exits with code 0 before `app` starts (visible in the compose log output as `ollama-pull-1 exited with code 0`).
2. Once `app` logs show the Spring Boot startup banner and "Started DevassistApplication", open `http://localhost:8080`, upload a document, and ask a question — confirm a real, grounded answer comes back with a source citation.
3. Run `docker compose down -v` to fully tear down (including the pulled model), then repeat steps 1-2 once more from a genuinely empty state, confirming the stack is self-contained and nothing was only working because of leftover state.

- [ ] **Step 6: Run the full suite one more time**

Run: `./mvnw test`
Expected: PASS, 238 tests, 0 failures, 0 errors (unaffected by this task — none of these files are part of the Maven build or test classpath).

- [ ] **Step 7: Commit**

```bash
git add Dockerfile .dockerignore docker-compose.yml .env.example .gitignore
git commit -m "feat: add Docker Compose deployment with a self-contained Ollama setup"
```

---

## Completion Checklist

- [ ] `./mvnw test` passes with 238 tests, 0 failures, 0 errors — the 218 tests present before this plan are still among them, none deleted or weakened.
- [ ] `GenerationService.generate(String, String)` and `SummaryGenerator.summarize(String, String)` still return a plain `String` — no caller (`RagQueryService`, `SummarizationService`) needed any change.
- [ ] `/actuator/metrics/rag.retrieval.duration`, `/actuator/metrics/rag.generation.duration`, `/actuator/metrics/rag.generation.tokens`, `/actuator/metrics/rag.summarization.duration`, and `/actuator/metrics/rag.summarization.tokens` are all populated after at least one query and one summarization request (manually verified alongside Task 4's Docker verification, or via `./mvnw spring-boot:run` + `curl localhost:8080/actuator/metrics/rag.generation.duration`).
- [ ] Every existing `*ControllerTest`'s `jsonPath(...)` assertions pass unmodified (BR-06) — proven by the full suite passing without those files being touched.
- [ ] All four exception handlers return `ErrorResponse` and have a working catch-all `Exception.class` → 500 handler that never leaks the original exception's message.
- [ ] `com.devassist.common` depends on nothing in `document`/`project`/`rag`/`eval` — the one-way dependency rule is preserved.
- [ ] `docker compose up` from a clean state brings up a fully working stack with no manual step beyond providing `.env`.
- [ ] No API key appears in `Dockerfile`, `docker-compose.yml`, or any committed file.
