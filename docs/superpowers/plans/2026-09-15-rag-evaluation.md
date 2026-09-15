# RAG Evaluation Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the RAG system a repeatable, automated way to score answer quality against a fixed question set, so a config change (chunk size, threshold, prompt wording) can be checked for regressions instead of eyeballed.

**Architecture:** A new `com.devassist.eval` package seeds a dedicated project with two fixed documents at startup, runs a checked-in question set through the real `RagQueryService.answer(...)` path, and scores each result — via LLM-as-judge for real answers, via direct comparison for correctly-declined refusals — behind a single `POST /api/eval/run` endpoint.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring AI 2.0.1 (existing `ChatClient.Builder` pattern), Jackson (`ObjectMapper`, already on the classpath — no new dependency).

**Spec:** `specs/rag-evaluation.md`

## Two Corrections to the Spec, Made While Planning

The spec's own §3 states the dependency direction as one-way: "eval depends on
rag... never the reverse." Two of its own component placements violate that
if taken literally, and this plan corrects both. Both corrections are
behaviour-preserving — only file location changes.

1. **`EvaluationScore` moves to `com.devassist.rag`, not `com.devassist.eval`.**
   `RagAnswerResponse` (in `rag`) must reference this type. If it lived in
   `eval`, `rag` would depend on `eval` — backwards. It lives beside
   `RagAnswerResponse` and `SourceReference`, which it resembles in role.

2. **The `EvalCorpusNotReadyException` → 503 mapping is its own
   `@RestControllerAdvice` in `com.devassist.eval`, not an addition to
   `RagExceptionHandler`.** `RagExceptionHandler` lives in `rag`; adding a
   handler for an `eval`-package exception would make `rag` depend on `eval`.
   A second, small advice class scoped to `EvalController` alone keeps the
   direction correct. Behaviour (status code, message shape) is identical to
   what the spec describes.

## Global Constraints

- Java 17 / Spring Boot 4.1.1 fixed — no framework changes.
- Maven Wrapper only (`./mvnw`), never a global `mvn`.
- Constructor injection only; no field or setter injection.
- Immutable records for all DTOs.
- **No new dependencies.** Judge parsing uses `ObjectMapper`, already provided
  by Spring Boot's Jackson auto-configuration.
- Tests must pass with no Ollama, no Gemini key, no network access — mock
  every external call, same as the RAG core.
- The 165 tests passing before this plan must all still pass. Do not delete
  or weaken any existing test.
- Comment only non-obvious "why", never "what".
- Nothing in `com.devassist.rag`, `com.devassist.document`, or
  `com.devassist.project` is modified except `RagAnswerResponse.java`
  (Task 1) — everything else is new, in `com.devassist.eval`.

---

### Task 1: `EvaluationScore` type and the `RagAnswerResponse` migration

Gives the reserved `evaluation` field its real type. Verified safe: the only
construction site in the whole codebase is `RagAnswerResponse.of(...)`,
which always passes `null` — assignable to any reference type, so this is a
pure type change with zero behavioural risk.

**Files:**
- Create: `src/main/java/com/devassist/rag/EvaluationScore.java`
- Modify: `src/main/java/com/devassist/rag/RagAnswerResponse.java`
- Test: `src/test/java/com/devassist/rag/EvaluationScoreTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `EvaluationScore(Integer faithfulness, Integer completeness, Boolean correctlyDeclined, String reasoning, Method method)` with `enum Method { JUDGED, PROGRAMMATIC, UNSCORABLE }`. `RagAnswerResponse.evaluation` is now typed `EvaluationScore` instead of `Object`.

- [ ] **Step 1: Write the failing test**

```java
package com.devassist.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScoreTest {

	@Test
	void carriesJudgedScoresWithNoCorrectlyDeclinedValue() {
		EvaluationScore score = new EvaluationScore(5, 4, null, "Faithful and complete.",
				EvaluationScore.Method.JUDGED);

		assertThat(score.faithfulness()).isEqualTo(5);
		assertThat(score.completeness()).isEqualTo(4);
		assertThat(score.correctlyDeclined()).isNull();
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.JUDGED);
	}

	@Test
	void carriesProgrammaticCorrectlyDeclinedWithNoJudgeScores() {
		EvaluationScore score = new EvaluationScore(null, null, true,
				"Question was marked unanswerable and the system correctly declined.",
				EvaluationScore.Method.PROGRAMMATIC);

		assertThat(score.faithfulness()).isNull();
		assertThat(score.completeness()).isNull();
		assertThat(score.correctlyDeclined()).isTrue();
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.PROGRAMMATIC);
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvaluationScoreTest`
Expected: FAIL to compile — `EvaluationScore` does not exist.

- [ ] **Step 3: Create `EvaluationScore`**

```java
package com.devassist.rag;

public record EvaluationScore(
		Integer faithfulness,
		Integer completeness,
		Boolean correctlyDeclined,
		String reasoning,
		Method method
) {

	public enum Method {
		JUDGED,
		PROGRAMMATIC,
		UNSCORABLE
	}
}
```

- [ ] **Step 4: Change `RagAnswerResponse`'s field type**

In `RagAnswerResponse.java`, change the record component from `Object evaluation` to `EvaluationScore evaluation`. The rest of the file (the `of(...)` factory, the `Status` enum) is unchanged — `of(...)` already passes `null`, which remains valid.

- [ ] **Step 5: Run the test to verify it passes, then the full suite**

Run: `./mvnw test -Dtest=EvaluationScoreTest`
Expected: PASS.

Run: `./mvnw test`
Expected: PASS — all 165 prior tests plus the 2 new ones, 167 total, 0 failures. This is the check that the type change did not break `RagQueryServiceTest`, `RagQueryControllerTest`, or `GenerationServiceTest`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/rag/EvaluationScore.java src/main/java/com/devassist/rag/RagAnswerResponse.java src/test/java/com/devassist/rag/EvaluationScoreTest.java
git commit -m "feat: give the reserved evaluation field its real type"
```

---

### Task 2: `EvalProperties`, `EvalCorpusNotReadyException`, and its exception handler

Configuration and the one new exception type everything else in this plan depends on.

**Files:**
- Create: `src/main/java/com/devassist/eval/EvalProperties.java`
- Create: `src/main/java/com/devassist/eval/EvalCorpusNotReadyException.java`
- Create: `src/main/java/com/devassist/eval/EvalExceptionHandler.java`
- Create: `src/main/java/com/devassist/eval/EvalConfiguration.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/devassist/eval/EvalPropertiesTest.java`
- Test: `src/test/java/com/devassist/eval/EvalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `EvalProperties(String projectName, String datasetPath, double minFaithfulness, double minCompleteness, double minCorrectlyDeclinedRate)`; `EvalCorpusNotReadyException` (unchecked, no-arg message baked in); an active `@RestControllerAdvice` mapping it to 503.

- [ ] **Step 1: Add the eval properties to `application.properties`**

Append:

```properties
devassist.eval.project-name=RAG Evaluation Corpus
devassist.eval.dataset-path=classpath:eval/eval-dataset.json
devassist.eval.min-faithfulness=4.0
devassist.eval.min-completeness=4.0
devassist.eval.min-correctly-declined-rate=1.0
```

- [ ] **Step 2: Write the failing properties-binding test**

```java
package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EvalPropertiesTest {

	@Autowired
	private EvalProperties properties;

	@Test
	void bindsDefaultsFromApplicationProperties() {
		assertThat(properties.projectName()).isEqualTo("RAG Evaluation Corpus");
		assertThat(properties.datasetPath()).isEqualTo("classpath:eval/eval-dataset.json");
		assertThat(properties.minFaithfulness()).isEqualTo(4.0);
		assertThat(properties.minCompleteness()).isEqualTo(4.0);
		assertThat(properties.minCorrectlyDeclinedRate()).isEqualTo(1.0);
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvalPropertiesTest`
Expected: FAIL to compile — `EvalProperties` does not exist.

- [ ] **Step 4: Create `EvalProperties` and `EvalConfiguration`**

```java
package com.devassist.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devassist.eval")
public record EvalProperties(
		String projectName,
		String datasetPath,
		double minFaithfulness,
		double minCompleteness,
		double minCorrectlyDeclinedRate
) {
}
```

```java
package com.devassist.eval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfiguration {
}
```

- [ ] **Step 5: Run the properties test to verify it passes**

Run: `./mvnw test -Dtest=EvalPropertiesTest`
Expected: PASS.

- [ ] **Step 6: Write the failing exception handler test**

```java
package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvalController.class)
class EvalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EvaluationService evaluationService;

	@Test
	void returnsServiceUnavailableWhenCorpusIsNotReady() throws Exception {
		when(evaluationService.runEvaluation()).thenThrow(new EvalCorpusNotReadyException());

		mockMvc.perform(post("/api/eval/run").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.message").value(
						"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet."));
	}
}
```

This test references `EvalController` and `EvaluationService`, which do not
exist until Tasks 6-7. It is written now (failing to compile) because the
exception type and its handler are this task's concern; **do not implement
`EvalController`/`EvaluationService` in this task** — leave this test
failing to compile and move on. Task 7 will make it compile and pass; note
its existence in Task 7's dispatch.

Actually — to keep this task's own test cycle real and independently
verifiable (per the plan's own rule that no task ships red), write the
exception handler test against a **minimal throwaway controller** defined
in the test file itself instead, so this task is fully self-contained:

```java
package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvalExceptionHandlerTest.ThrowingController.class)
class EvalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@RestController
	static class ThrowingController {
		@GetMapping("/test-eval-corpus-not-ready")
		void throwIt() {
			throw new EvalCorpusNotReadyException();
		}
	}

	@Test
	void returnsServiceUnavailableWithTheFixedMessage() throws Exception {
		mockMvc.perform(get("/test-eval-corpus-not-ready"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.message").value(
						"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet."));
	}
}
```

For this to exercise `EvalExceptionHandler`, its `@RestControllerAdvice`
must be scoped broadly enough to catch a test-local nested controller —
use `assignableTypes = EvalController.class` in the real production
annotation (Step 8) exactly as specified, but note this specific test
proves the *handler method's* behaviour in isolation via a plain
`@ExceptionHandler`-per-advice unit test instead of relying on
`assignableTypes` matching the throwaway class. Write the assertion this
way instead — directly against the handler, no Spring context needed:

```java
package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalExceptionHandlerTest {

	private final EvalExceptionHandler handler = new EvalExceptionHandler();

	@Test
	void mapsCorpusNotReadyToServiceUnavailableWithTheFixedMessage() {
		Map<String, Object> body = handler.handleCorpusNotReady(new EvalCorpusNotReadyException());

		assertThat(body).containsEntry("status", HttpStatus.SERVICE_UNAVAILABLE.value());
		assertThat(body).containsEntry("message",
				"Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet.");
	}
}
```

Use this final version — a plain unit test on the handler object, no
`@WebMvcTest`, no throwaway controller. It is simpler, needs no Spring
context, and proves exactly what this task owns: the mapping from
exception to response body.

- [ ] **Step 7: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvalExceptionHandlerTest`
Expected: FAIL to compile — `EvalCorpusNotReadyException` and `EvalExceptionHandler` do not exist.

- [ ] **Step 8: Create the exception and its handler**

```java
package com.devassist.eval;

public class EvalCorpusNotReadyException extends RuntimeException {

	public EvalCorpusNotReadyException() {
		super("Evaluation corpus not ready — is Ollama running? No documents have been indexed into the eval project yet.");
	}
}
```

```java
package com.devassist.eval;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// Scoped to EvalController only, and deliberately separate from
// com.devassist.rag.RagExceptionHandler: that class lives in the `rag`
// package, and adding a handler for an `eval`-package exception there
// would make `rag` depend on `eval` - backwards, per this feature's own
// one-way dependency rule (eval depends on rag, never the reverse).
@RestControllerAdvice(assignableTypes = EvalController.class)
public class EvalExceptionHandler {

	@ExceptionHandler(EvalCorpusNotReadyException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public Map<String, Object> handleCorpusNotReady(EvalCorpusNotReadyException ex) {
		return Map.of("status", HttpStatus.SERVICE_UNAVAILABLE.value(), "message", ex.getMessage());
	}
}
```

This references `EvalController`, which does not exist until Task 7 — that
is fine, `assignableTypes` takes a class literal that must exist at
compile time. **Create a minimal placeholder now** so this task compiles
standalone, which Task 7 will replace with the real implementation:

```java
package com.devassist.eval;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class EvalController {
}
```

- [ ] **Step 9: Run the test to verify it passes, then the full suite**

Run: `./mvnw test -Dtest=EvalExceptionHandlerTest`
Expected: PASS.

Run: `./mvnw test`
Expected: PASS, 169 tests total, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/devassist/eval/ src/main/resources/application.properties src/test/java/com/devassist/eval/EvalPropertiesTest.java src/test/java/com/devassist/eval/EvalExceptionHandlerTest.java
git commit -m "feat: add eval configuration and the corpus-not-ready 503 mapping"
```

---

### Task 3: `EvalCorpusLocator` and `EvalCorpusSeeder`

The resilient startup seeding from spec §5, plus a small shared lookup used
by both the seeder and (in Task 6) the evaluation service — factored out so
the "find the eval project by name" logic exists in exactly one place.

**Files:**
- Create: `src/main/java/com/devassist/eval/EvalCorpusLocator.java`
- Create: `src/main/java/com/devassist/eval/EvalCorpusSeeder.java`
- Create: `src/main/resources/eval/product-policy.txt`
- Create: `src/main/resources/eval/engineering-practices.txt`
- Test: `src/test/java/com/devassist/eval/EvalCorpusLocatorTest.java`
- Test: `src/test/java/com/devassist/eval/EvalCorpusSeederTest.java`

**Interfaces:**
- Consumes: `com.devassist.project.ProjectService` (`findAll()` returns
  `List<Project>`; `Project.id()`/`Project.name()`; `create(CreateProjectRequest)`
  returns `Project`), `com.devassist.document.DocumentService`
  (`ingestText(String projectId, IngestTextRequest request)` returns
  `IngestResult`), `EvalProperties.projectName()`.
- Produces: `EvalCorpusLocator.findProjectId()` → `Optional<String>`, reused
  by `EvaluationService` in Task 6.

- [ ] **Step 1: Write the two fixed document files**

`src/main/resources/eval/product-policy.txt`:

```
Our physical products come with a 30-day return window from the date of delivery. Refunds are issued to the original payment method within 5 business days of receiving the returned item. All physical products carry a 1-year limited warranty covering manufacturing defects. Digital products are non-refundable once downloaded.
```

`src/main/resources/eval/engineering-practices.txt`:

```
All pull requests require at least two approvals before merging to the main branch. The team deploys to production every weekday afternoon using an automated pipeline. New backend services must be written in Java using the Spring Boot framework. Code coverage for new modules must be at least 80 percent.
```

- [ ] **Step 2: Write the failing locator test**

```java
package com.devassist.eval;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;

class EvalCorpusLocatorTest {

	private final EvalProperties properties = new EvalProperties("RAG Evaluation Corpus",
			"classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);

	@Test
	void findsTheProjectByConfiguredName() {
		ProjectService projectService = new ProjectService();
		Project created = projectService.create(new CreateProjectRequest("RAG Evaluation Corpus",
				"desc", "N/A", "https://example.com/devassist-eval"));
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);

		Optional<String> found = locator.findProjectId();

		assertThat(found).contains(created.id());
	}

	@Test
	void returnsEmptyWhenNoProjectHasThatName() {
		ProjectService projectService = new ProjectService();
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);

		assertThat(locator.findProjectId()).isEmpty();
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvalCorpusLocatorTest`
Expected: FAIL to compile — `EvalCorpusLocator` does not exist.

- [ ] **Step 4: Create `EvalCorpusLocator`**

```java
package com.devassist.eval;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.devassist.project.Project;
import com.devassist.project.ProjectService;

@Component
public class EvalCorpusLocator {

	private final ProjectService projectService;
	private final EvalProperties properties;

	public EvalCorpusLocator(ProjectService projectService, EvalProperties properties) {
		this.projectService = projectService;
		this.properties = properties;
	}

	public Optional<String> findProjectId() {
		return projectService.findAll().stream()
				.filter(project -> properties.projectName().equals(project.name()))
				.map(Project::id)
				.findFirst();
	}
}
```

- [ ] **Step 5: Run the locator test to verify it passes**

Run: `./mvnw test -Dtest=EvalCorpusLocatorTest`
Expected: PASS.

- [ ] **Step 6: Write the failing seeder test**

```java
package com.devassist.eval;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.DefaultResourceLoader;

import com.devassist.document.DocumentService;
import com.devassist.document.IngestTextRequest;
import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCorpusSeederTest {

	private final EvalProperties properties = new EvalProperties("RAG Evaluation Corpus",
			"classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);

	@Test
	void createsTheProjectAndIngestsBothDocumentsWhenNoneExists() throws Exception {
		ProjectService projectService = mock(ProjectService.class);
		DocumentService documentService = mock(DocumentService.class);
		Project created = new Project("eval-proj-1", "RAG Evaluation Corpus", "desc", "N/A",
				"https://example.com/devassist-eval");
		when(projectService.findAll()).thenReturn(List.of());
		when(projectService.create(any(CreateProjectRequest.class))).thenReturn(created);
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);
		EvalCorpusSeeder seeder = new EvalCorpusSeeder(projectService, documentService, locator, properties,
				new DefaultResourceLoader());

		seeder.run(mock(ApplicationArguments.class));

		verify(documentService, times(2)).ingestText(eq("eval-proj-1"), any(IngestTextRequest.class));
	}

	@Test
	void reusesAnExistingEvalProjectRatherThanCreatingAnother() throws Exception {
		ProjectService projectService = mock(ProjectService.class);
		DocumentService documentService = mock(DocumentService.class);
		Project existing = new Project("eval-proj-1", "RAG Evaluation Corpus", "desc", "N/A",
				"https://example.com/devassist-eval");
		when(projectService.findAll()).thenReturn(List.of(existing));
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);
		EvalCorpusSeeder seeder = new EvalCorpusSeeder(projectService, documentService, locator, properties,
				new DefaultResourceLoader());

		seeder.run(mock(ApplicationArguments.class));

		verify(projectService, times(0)).create(any(CreateProjectRequest.class));
		verify(documentService, times(2)).ingestText(eq("eval-proj-1"), any(IngestTextRequest.class));
	}

	// BR-01: a failure here (e.g. Ollama unreachable, surfacing when the
	// DocumentIngestedEvent listener tries to embed) must never propagate out
	// of run() and fail application startup.
	@Test
	void neverPropagatesAFailureFromIngestion() {
		ProjectService projectService = mock(ProjectService.class);
		DocumentService documentService = mock(DocumentService.class);
		Project created = new Project("eval-proj-1", "RAG Evaluation Corpus", "desc", "N/A",
				"https://example.com/devassist-eval");
		when(projectService.findAll()).thenReturn(List.of());
		when(projectService.create(any(CreateProjectRequest.class))).thenReturn(created);
		when(documentService.ingestText(any(), any())).thenThrow(new RuntimeException("Ollama unreachable"));
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);
		EvalCorpusSeeder seeder = new EvalCorpusSeeder(projectService, documentService, locator, properties,
				new DefaultResourceLoader());

		assertThatCode(() -> seeder.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
	}
}
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvalCorpusSeederTest`
Expected: FAIL to compile — `EvalCorpusSeeder` does not exist.

- [ ] **Step 8: Create `EvalCorpusSeeder`**

```java
package com.devassist.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import com.devassist.document.DocumentService;
import com.devassist.document.IngestTextRequest;
import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectService;

@Component
public class EvalCorpusSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(EvalCorpusSeeder.class);

	private final ProjectService projectService;
	private final DocumentService documentService;
	private final EvalCorpusLocator locator;
	private final EvalProperties properties;
	private final ResourceLoader resourceLoader;

	public EvalCorpusSeeder(ProjectService projectService, DocumentService documentService,
			EvalCorpusLocator locator, EvalProperties properties, ResourceLoader resourceLoader) {
		this.projectService = projectService;
		this.documentService = documentService;
		this.locator = locator;
		this.properties = properties;
		this.resourceLoader = resourceLoader;
	}

	// BR-01: must never block or fail application startup, regardless of
	// Ollama's availability - a failure here only means the eval corpus is not
	// ready yet, which /api/eval/run reports on its own (EvalCorpusNotReadyException).
	@Override
	public void run(ApplicationArguments args) {
		try {
			String projectId = locator.findProjectId().orElseGet(this::createEvalProject);
			// Content-hash dedup (rag-core BR-03) makes re-ingesting on every
			// restart a safe no-op once a document already exists, so there is
			// no need to check for existing documents before calling ingestText.
			documentService.ingestText(projectId, new IngestTextRequest("product-policy.txt",
					readResource("classpath:eval/product-policy.txt")));
			documentService.ingestText(projectId, new IngestTextRequest("engineering-practices.txt",
					readResource("classpath:eval/engineering-practices.txt")));
		}
		catch (Exception ex) {
			log.warn("Eval corpus seeding failed; POST /api/eval/run will report 503 until this succeeds", ex);
		}
	}

	private String createEvalProject() {
		Project project = projectService.create(new CreateProjectRequest(properties.projectName(),
				"Fixed corpus for the RAG evaluation harness", "N/A", "https://example.com/devassist-eval"));
		return project.id();
	}

	private String readResource(String location) {
		Resource resource = resourceLoader.getResource(location);
		try {
			return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Missing bundled eval resource: " + location, ex);
		}
	}
}
```

- [ ] **Step 9: Run the seeder tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=EvalCorpusSeederTest`
Expected: PASS — all 3 tests.

Run: `./mvnw test`
Expected: PASS, 174 tests total, 0 failures.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/devassist/eval/EvalCorpusLocator.java src/main/java/com/devassist/eval/EvalCorpusSeeder.java src/main/resources/eval/ src/test/java/com/devassist/eval/EvalCorpusLocatorTest.java src/test/java/com/devassist/eval/EvalCorpusSeederTest.java
git commit -m "feat: seed a resilient, idempotent eval corpus at startup"
```

---

### Task 4: `EvalQuestion` and `EvalDataset`

Fail-fast dataset loading (deliberately the opposite resilience posture from
Task 3's seeding — a malformed checked-in file is a developer error, not an
infrastructure hiccup).

**Files:**
- Create: `src/main/java/com/devassist/eval/EvalQuestion.java`
- Create: `src/main/java/com/devassist/eval/EvalDataset.java`
- Create: `src/main/resources/eval/eval-dataset.json`
- Test: `src/test/java/com/devassist/eval/EvalDatasetTest.java`

**Interfaces:**
- Consumes: `EvalProperties.datasetPath()`.
- Produces: `EvalQuestion(String question, boolean answerable)`;
  `EvalDataset.questions()` → `List<EvalQuestion>`, consumed by
  `EvaluationService` in Task 6.

- [ ] **Step 1: Write the dataset file**

`src/main/resources/eval/eval-dataset.json` — 6 answerable questions
(matched to the two seeded documents) and 2 deliberately unanswerable ones:

```json
[
  { "question": "What is the return window for physical products?", "answerable": true },
  { "question": "How many business days does a refund take to process?", "answerable": true },
  { "question": "What is the warranty period on physical products?", "answerable": true },
  { "question": "How many approvals does a pull request need before merging?", "answerable": true },
  { "question": "What framework must new backend services use?", "answerable": true },
  { "question": "What is the minimum code coverage required for new modules?", "answerable": true },
  { "question": "How many remote work days per week does the company allow?", "answerable": false },
  { "question": "Which cloud provider hosts the production database?", "answerable": false }
]
```

- [ ] **Step 2: Write the failing test**

This test loads the REAL checked-in file — no mocking — so it doubles as a
regression check that the file itself stays valid JSON with the expected
shape. It needs no Ollama, Gemini, or network, only a classpath resource
read.

```java
package com.devassist.eval;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDatasetTest {

	private final EvalProperties properties = new EvalProperties("RAG Evaluation Corpus",
			"classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);

	@Test
	void loadsTheCheckedInDatasetFile() {
		EvalDataset dataset = new EvalDataset(new DefaultResourceLoader(), new ObjectMapper(), properties);

		assertThat(dataset.questions()).hasSize(8);
		assertThat(dataset.questions()).filteredOn(EvalQuestion::answerable).hasSize(6);
		assertThat(dataset.questions()).filteredOn(q -> !q.answerable()).hasSize(2);
		assertThat(dataset.questions().get(0).question())
				.isEqualTo("What is the return window for physical products?");
	}

	@Test
	void failsFastOnAMissingDatasetFile() {
		EvalProperties badPath = new EvalProperties("RAG Evaluation Corpus",
				"classpath:eval/does-not-exist.json", 4.0, 4.0, 1.0);

		org.assertj.core.api.Assertions.assertThatThrownBy(
				() -> new EvalDataset(new DefaultResourceLoader(), new ObjectMapper(), badPath))
				.isInstanceOf(IllegalStateException.class);
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=EvalDatasetTest`
Expected: FAIL to compile — `EvalQuestion`/`EvalDataset` do not exist.

- [ ] **Step 4: Create `EvalQuestion` and `EvalDataset`**

```java
package com.devassist.eval;

public record EvalQuestion(String question, boolean answerable) {
}
```

```java
package com.devassist.eval;

import java.io.IOException;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class EvalDataset {

	private final List<EvalQuestion> questions;

	// Loaded eagerly in the constructor, at application startup, so a
	// malformed checked-in file fails the build/boot immediately - unlike
	// EvalCorpusSeeder's resilience posture, this is a deterministic local
	// file with no external dependency, so there is nothing to retry and no
	// reason to hide a bug in it.
	public EvalDataset(ResourceLoader resourceLoader, ObjectMapper objectMapper, EvalProperties properties) {
		Resource resource = resourceLoader.getResource(properties.datasetPath());
		try {
			this.questions = objectMapper.readValue(resource.getInputStream(), new TypeReference<List<EvalQuestion>>() {
			});
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not load eval dataset from " + properties.datasetPath(), ex);
		}
	}

	public List<EvalQuestion> questions() {
		return questions;
	}
}
```

- [ ] **Step 5: Run the test to verify it passes, then the full suite**

Run: `./mvnw test -Dtest=EvalDatasetTest`
Expected: PASS — both tests.

Run: `./mvnw test`
Expected: PASS, 176 tests total, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/eval/EvalQuestion.java src/main/java/com/devassist/eval/EvalDataset.java src/main/resources/eval/eval-dataset.json src/test/java/com/devassist/eval/EvalDatasetTest.java
git commit -m "feat: load the eval question set, failing fast on a malformed file"
```

---

### Task 5: `JudgeService`

The LLM-as-judge component, with defensive parsing of untrusted output —
same discipline as citation-marker parsing in the RAG core.

**Files:**
- Create: `src/main/java/com/devassist/eval/JudgeService.java`
- Test: `src/test/java/com/devassist/eval/JudgeServiceTest.java`

**Interfaces:**
- Consumes: `ObjectProvider<ChatClient.Builder>` (same lazy-resolution
  pattern as `com.devassist.rag.GenerationService`, for the same reason —
  do not force the Gemini bean to build outside of an actual judge call);
  `com.devassist.rag.SourceReference` (`title()`, `excerpt()`).
- Produces: `JudgeService.judgeAnswered(String question, String answer, List<SourceReference> sources)` → `com.devassist.rag.EvaluationScore` with `method = JUDGED` or `UNSCORABLE`, never throws.

- [ ] **Step 1: Write the failing tests**

```java
package com.devassist.eval;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.devassist.rag.EvaluationScore;
import com.devassist.rag.SourceReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeServiceTest {

	private JudgeService serviceReturning(String rawModelOutput) {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(org.mockito.ArgumentMatchers.any()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(rawModelOutput)))));

		@SuppressWarnings("unchecked")
		ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
		when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));

		return new JudgeService(builderProvider, new ObjectMapper());
	}

	private final List<SourceReference> sources = List.of(
			new SourceReference("doc-1", "product-policy.txt", 0, 0.81, true, "30-day return window"));

	@Test
	void parsesCleanJson() {
		JudgeService service = serviceReturning(
				"{\"faithfulness\": 5, \"completeness\": 4, \"reasoning\": \"Matches the source exactly.\"}");

		EvaluationScore score = service.judgeAnswered("q", "30 days. [1]", sources);

		assertThat(score.faithfulness()).isEqualTo(5);
		assertThat(score.completeness()).isEqualTo(4);
		assertThat(score.reasoning()).isEqualTo("Matches the source exactly.");
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.JUDGED);
	}

	@Test
	void parsesJsonWrappedInMarkdownFences() {
		JudgeService service = serviceReturning(
				"```json\n{\"faithfulness\": 3, \"completeness\": 3, \"reasoning\": \"Partial.\"}\n```");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.faithfulness()).isEqualTo(3);
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.JUDGED);
	}

	@Test
	void treatsGarbageOutputAsUnscorableRatherThanThrowing() {
		JudgeService service = serviceReturning("I refuse to output JSON today.");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.method()).isEqualTo(EvaluationScore.Method.UNSCORABLE);
		assertThat(score.faithfulness()).isNull();
		assertThat(score.reasoning()).isNotBlank();
	}

	@Test
	void treatsJsonMissingRequiredFieldsAsUnscorable() {
		JudgeService service = serviceReturning("{\"reasoning\": \"I forgot the scores.\"}");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.method()).isEqualTo(EvaluationScore.Method.UNSCORABLE);
	}

	@Test
	void sendsTheQuestionAnswerAndSourceExcerptsInThePrompt() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> promptCaptor =
				org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
		when(chatModel.call(promptCaptor.capture())).thenReturn(
				new ChatResponse(List.of(new Generation(new AssistantMessage(
						"{\"faithfulness\": 5, \"completeness\": 5, \"reasoning\": \"ok\"}")))));
		@SuppressWarnings("unchecked")
		ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
		when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));
		JudgeService service = new JudgeService(builderProvider, new ObjectMapper());

		service.judgeAnswered("What is the return window?", "30 days. [1]", sources);

		String promptText = promptCaptor.getValue().getContents();
		assertThat(promptText).contains("What is the return window?");
		assertThat(promptText).contains("30 days. [1]");
		assertThat(promptText).contains("30-day return window");
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=JudgeServiceTest`
Expected: FAIL to compile — `JudgeService` does not exist.

- [ ] **Step 3: Implement `JudgeService`**

```java
package com.devassist.eval;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.devassist.rag.EvaluationScore;
import com.devassist.rag.SourceReference;

@Service
public class JudgeService {

	private static final String SYSTEM_PROMPT = """
			You are a strict evaluator of another AI's answer to a question, given the
			context it was allowed to use. Respond with ONLY a single JSON object, no
			markdown fences, no extra text, in exactly this shape:
			{"faithfulness": <1-5 integer>, "completeness": <1-5 integer>, "reasoning": "<one sentence>"}

			faithfulness: does the answer make only claims supported by the context?
			completeness: does the answer address the whole question, not just part of it?
			""";

	// Matches a JSON object even when the model wraps it in markdown code
	// fences despite being told not to - LLMs do this often enough that it
	// must be handled, not treated as exceptional.
	private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

	private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
	private final ObjectMapper objectMapper;

	public JudgeService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider, ObjectMapper objectMapper) {
		this.chatClientBuilderProvider = chatClientBuilderProvider;
		this.objectMapper = objectMapper;
	}

	public EvaluationScore judgeAnswered(String question, String answer, List<SourceReference> sources) {
		String context = buildContext(sources);
		ChatClient chatClient = chatClientBuilderProvider.getObject().defaultSystem(SYSTEM_PROMPT).build();
		String raw = chatClient.prompt()
				.user("Context:\n%s\n\nQuestion: %s\n\nAnswer to evaluate: %s".formatted(context, question, answer))
				.call()
				.content();
		return parse(raw);
	}

	private String buildContext(List<SourceReference> sources) {
		StringBuilder builder = new StringBuilder();
		for (SourceReference source : sources) {
			builder.append(source.title()).append(": ").append(source.excerpt()).append('\n');
		}
		return builder.toString();
	}

	// The judge's output is untrusted LLM text, same as citation-marker
	// parsing in the RAG core - it must degrade to UNSCORABLE, never throw.
	private EvaluationScore parse(String raw) {
		if (raw == null) {
			return unscorable("Judge returned no content");
		}
		Matcher matcher = JSON_OBJECT.matcher(raw);
		if (!matcher.find()) {
			return unscorable("Judge response contained no JSON object: " + raw);
		}
		try {
			JudgeJson parsed = objectMapper.readValue(matcher.group(), JudgeJson.class);
			if (parsed.faithfulness() == null || parsed.completeness() == null) {
				return unscorable("Judge response missing faithfulness or completeness: " + raw);
			}
			return new EvaluationScore(parsed.faithfulness(), parsed.completeness(), null,
					parsed.reasoning() != null ? parsed.reasoning() : "(no reasoning given)",
					EvaluationScore.Method.JUDGED);
		}
		catch (Exception ex) {
			return unscorable("Could not parse judge response: " + ex.getMessage());
		}
	}

	private EvaluationScore unscorable(String reasoning) {
		return new EvaluationScore(null, null, null, reasoning, EvaluationScore.Method.UNSCORABLE);
	}

	private record JudgeJson(Integer faithfulness, Integer completeness, String reasoning) {
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=JudgeServiceTest`
Expected: PASS — all 5 tests.

Run: `./mvnw test`
Expected: PASS, 181 tests total, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/eval/JudgeService.java src/test/java/com/devassist/eval/JudgeServiceTest.java
git commit -m "feat: add LLM-as-judge scoring with defensive response parsing"
```

---

### Task 6: `EvaluationService`

The orchestrator — the two-track scoring split, per-question failure
isolation, and the aggregate math.

**Files:**
- Create: `src/main/java/com/devassist/eval/EvaluationService.java`
- Create: `src/main/java/com/devassist/eval/EvalResultEntry.java`
- Create: `src/main/java/com/devassist/eval/EvalSummary.java`
- Test: `src/test/java/com/devassist/eval/EvaluationServiceTest.java`

**Interfaces:**
- Consumes: `EvalCorpusLocator.findProjectId()` → `Optional<String>`;
  `EvalDataset.questions()` → `List<EvalQuestion>`;
  `com.devassist.document.DocumentService.findByProject(String)` →
  `List<Document>`; `com.devassist.rag.IndexStatusService.get(String documentId)`
  → `Optional<IndexStatus>` with `IndexStatus.state()` an enum including
  `INDEXED`; `com.devassist.rag.RagQueryService.answer(String projectId, String question)`
  → `RagAnswerResponse`; `JudgeService.judgeAnswered(...)` → `EvaluationScore`.
- Produces: `EvaluationService.runEvaluation()` → `List<EvalResultEntry>`
  plus a computed `EvalSummary`, consumed by `EvalController` in Task 7.
  `EvalResultEntry(String question, boolean expectedAnswerable, RagAnswerResponse response, boolean outcomeMatchedExpectation, String failureReason)`.

One addition beyond the spec's literal text, made here and flagged rather
than silently added: **`outcomeMatchedExpectation`** on every successful
entry, computed the same way regardless of branch —
`(response.status() == ANSWERED) == question.answerable()`. The spec's
BR-03 branches purely on the *actual* status to decide how to score, which
leaves one real failure mode invisible: a question marked `answerable:
false` that the system incorrectly answers anyway would go through the
normal `ANSWERED` judging path and never get flagged as a mismatch. This
field makes that mismatch visible without changing BR-03's branching logic
or the aggregate formulas in BR-07 — it is additive detail on the
per-question entry, not a new aggregate.

- [ ] **Step 1: Write the failing tests**

```java
package com.devassist.eval;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.devassist.document.Document;
import com.devassist.document.DocumentService;
import com.devassist.document.SourceType;
import com.devassist.rag.EvaluationScore;
import com.devassist.rag.IndexStatus;
import com.devassist.rag.IndexStatusService;
import com.devassist.rag.RagAnswerResponse;
import com.devassist.rag.RagQueryService;
import com.devassist.rag.SourceReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {

	private EvalCorpusLocator locator;
	private EvalDataset dataset;
	private DocumentService documentService;
	private IndexStatusService indexStatusService;
	private RagQueryService ragQueryService;
	private JudgeService judgeService;
	private EvalProperties properties;
	private EvaluationService service;

	private final Document indexedDoc = new Document("doc-1", "eval-proj", "product-policy.txt", SourceType.TEXT,
			"body", "hash", Instant.now());

	@BeforeEach
	void setUp() {
		locator = mock(EvalCorpusLocator.class);
		dataset = mock(EvalDataset.class);
		documentService = mock(DocumentService.class);
		indexStatusService = mock(IndexStatusService.class);
		ragQueryService = mock(RagQueryService.class);
		judgeService = mock(JudgeService.class);
		properties = new EvalProperties("RAG Evaluation Corpus", "classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);
		service = new EvaluationService(locator, dataset, documentService, indexStatusService, ragQueryService,
				judgeService, properties);

		when(locator.findProjectId()).thenReturn(Optional.of("eval-proj"));
		when(documentService.findByProject("eval-proj")).thenReturn(List.of(indexedDoc));
		when(indexStatusService.get("doc-1")).thenReturn(Optional.of(IndexStatus.indexed("doc-1", 1)));
	}

	@Test
	void throwsCorpusNotReadyWhenNoDocumentsExist() {
		when(documentService.findByProject("eval-proj")).thenReturn(List.of());

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runEvaluation())
				.isInstanceOf(EvalCorpusNotReadyException.class);
	}

	@Test
	void throwsCorpusNotReadyWhenDocumentsExistButNoneAreIndexedYet() {
		when(indexStatusService.get("doc-1")).thenReturn(Optional.of(IndexStatus.failed("doc-1", "embedding down")));

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.runEvaluation())
				.isInstanceOf(EvalCorpusNotReadyException.class);
	}

	@Test
	void answeredQuestionsAreScoredByTheJudgeNotProgrammatically() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		RagAnswerResponse answered = new RagAnswerResponse("q1", "30 days. [1]", RagAnswerResponse.Status.ANSWERED,
				List.of(source), 100, null);
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(answered);
		EvaluationScore judged = new EvaluationScore(5, 5, null, "great", EvaluationScore.Method.JUDGED);
		when(judgeService.judgeAnswered(eq("q1"), eq("30 days. [1]"), any())).thenReturn(judged);

		List<EvalResultEntry> results = service.runEvaluation().results();

		verify(judgeService).judgeAnswered(eq("q1"), eq("30 days. [1]"), any());
		assertThat(results).hasSize(1);
		assertThat(results.get(0).response().evaluation()).isEqualTo(judged);
		assertThat(results.get(0).outcomeMatchedExpectation()).isTrue();
	}

	@Test
	void insufficientContextQuestionsAreScoredProgrammaticallyNotByTheJudge() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("unanswerable q", false)));
		RagAnswerResponse declined = new RagAnswerResponse("unanswerable q", RagQueryService.NO_CONTEXT_ANSWER,
				RagAnswerResponse.Status.INSUFFICIENT_CONTEXT, List.of(), 50, null);
		when(ragQueryService.answer("eval-proj", "unanswerable q")).thenReturn(declined);

		List<EvalResultEntry> results = service.runEvaluation().results();

		verify(judgeService, never()).judgeAnswered(anyString(), anyString(), any());
		EvaluationScore score = results.get(0).response().evaluation();
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.PROGRAMMATIC);
		assertThat(score.correctlyDeclined()).isTrue();
		assertThat(results.get(0).outcomeMatchedExpectation()).isTrue();
	}

	@Test
	void flagsAMismatchWhenAnAnswerableQuestionIsWronglyDeclined() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		RagAnswerResponse declined = new RagAnswerResponse("q1", RagQueryService.NO_CONTEXT_ANSWER,
				RagAnswerResponse.Status.INSUFFICIENT_CONTEXT, List.of(), 50, null);
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(declined);

		EvalResultEntry entry = service.runEvaluation().results().get(0);

		assertThat(entry.response().evaluation().correctlyDeclined()).isFalse();
		assertThat(entry.outcomeMatchedExpectation()).isFalse();
	}

	@Test
	void oneFailingQuestionDoesNotStopTheRun() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("bad", true), new EvalQuestion("good", true)));
		when(ragQueryService.answer("eval-proj", "bad")).thenThrow(new RuntimeException("Gemini down"));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		RagAnswerResponse ok = new RagAnswerResponse("good", "ans [1]", RagAnswerResponse.Status.ANSWERED,
				List.of(source), 100, null);
		when(ragQueryService.answer("eval-proj", "good")).thenReturn(ok);
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenReturn(new EvaluationScore(5, 5, null, "ok", EvaluationScore.Method.JUDGED));

		EvalReportResponse report = service.runEvaluation();

		assertThat(report.results()).hasSize(2);
		assertThat(report.results().get(0).failureReason()).isEqualTo("Gemini down");
		assertThat(report.results().get(0).response()).isNull();
		assertThat(report.results().get(1).response()).isNotNull();
		assertThat(report.summary().failedQuestions()).isEqualTo(1);
	}

	@Test
	void averagesAreComputedOverOnlyTheCorrectSubsets() {
		when(dataset.questions()).thenReturn(List.of(
				new EvalQuestion("answerable-1", true), new EvalQuestion("unanswerable-1", false)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		when(ragQueryService.answer("eval-proj", "answerable-1")).thenReturn(new RagAnswerResponse("answerable-1",
				"ans [1]", RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenReturn(new EvaluationScore(4, 5, null, "ok", EvaluationScore.Method.JUDGED));
		when(ragQueryService.answer("eval-proj", "unanswerable-1")).thenReturn(new RagAnswerResponse(
				"unanswerable-1", RagQueryService.NO_CONTEXT_ANSWER, RagAnswerResponse.Status.INSUFFICIENT_CONTEXT,
				List.of(), 50, null));

		EvalSummary summary = service.runEvaluation().summary();

		assertThat(summary.averageFaithfulness()).isEqualTo(4.0);
		assertThat(summary.averageCompleteness()).isEqualTo(5.0);
		assertThat(summary.correctlyDeclinedRate()).isEqualTo(1.0);
		assertThat(summary.answerableQuestions()).isEqualTo(1);
		assertThat(summary.unanswerableQuestions()).isEqualTo(1);
	}

	// BR-04, explicitly required by specs/rag-evaluation.md §11: a judge call
	// that throws outright (not just returns malformed text - JudgeService's
	// own parsing failures are covered in JudgeServiceTest) must not abort
	// the run either.
	@Test
	void aJudgeCallThatThrowsIsCountedAsFailedNotAborted() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(new RagAnswerResponse("q1", "ans [1]",
				RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenThrow(new RuntimeException("Gemini unreachable"));

		EvalReportResponse report = service.runEvaluation();

		assertThat(report.results()).hasSize(1);
		assertThat(report.results().get(0).response()).isNotNull();
		assertThat(report.results().get(0).response().evaluation().method())
				.isEqualTo(EvaluationScore.Method.UNSCORABLE);
		assertThat(report.summary().failedQuestions()).isEqualTo(1);
		assertThat(report.summary().passed()).isFalse();
	}

	@Test
	void passedIsFalseWhenAnyQuestionFailedEvenIfScoresAreHighEnough() {
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("bad", true)));
		when(ragQueryService.answer("eval-proj", "bad")).thenThrow(new RuntimeException("down"));

		EvalSummary summary = service.runEvaluation().summary();

		assertThat(summary.failedQuestions()).isEqualTo(1);
		assertThat(summary.passed()).isFalse();
	}

	@Test
	void passedIsFalseWhenFaithfulnessIsBelowTheConfiguredMinimum() {
		properties = new EvalProperties("RAG Evaluation Corpus", "classpath:eval/eval-dataset.json", 4.5, 4.0, 1.0);
		service = new EvaluationService(locator, dataset, documentService, indexStatusService, ragQueryService,
				judgeService, properties);
		when(dataset.questions()).thenReturn(List.of(new EvalQuestion("q1", true)));
		SourceReference source = new SourceReference("doc-1", "product-policy.txt", 0, 0.8, true, "excerpt");
		when(ragQueryService.answer("eval-proj", "q1")).thenReturn(new RagAnswerResponse("q1", "ans [1]",
				RagAnswerResponse.Status.ANSWERED, List.of(source), 100, null));
		when(judgeService.judgeAnswered(anyString(), anyString(), any()))
				.thenReturn(new EvaluationScore(3, 5, null, "weak", EvaluationScore.Method.JUDGED));

		EvalSummary summary = service.runEvaluation().summary();

		assertThat(summary.passed()).isFalse();
	}
}
```

Note this test file references `EvalReportResponse` (Task 7's DTO wrapping
`results`/`summary`) as the return type of `runEvaluation()`. Declare that
return type now as part of this task, and create a minimal
`EvalReportResponse(List<EvalResultEntry> results, EvalSummary summary)`
record here — Task 7 reuses it unchanged for the controller's response
body, it does not redefine it.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=EvaluationServiceTest`
Expected: FAIL to compile — `EvaluationService`, `EvalResultEntry`,
`EvalSummary`, `EvalReportResponse` do not exist.

- [ ] **Step 3: Create the DTOs**

```java
package com.devassist.eval;

import com.devassist.rag.RagAnswerResponse;

public record EvalResultEntry(
		String question,
		boolean expectedAnswerable,
		RagAnswerResponse response,
		boolean outcomeMatchedExpectation,
		String failureReason
) {
}
```

```java
package com.devassist.eval;

public record EvalSummary(
		int totalQuestions,
		int answerableQuestions,
		int unanswerableQuestions,
		Double averageFaithfulness,
		Double averageCompleteness,
		Double correctlyDeclinedRate,
		int failedQuestions,
		boolean passed
) {
}
```

```java
package com.devassist.eval;

import java.util.List;

public record EvalReportResponse(List<EvalResultEntry> results, EvalSummary summary) {
}
```

- [ ] **Step 4: Implement `EvaluationService`**

```java
package com.devassist.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import org.springframework.stereotype.Service;

import com.devassist.document.Document;
import com.devassist.document.DocumentService;
import com.devassist.rag.EvaluationScore;
import com.devassist.rag.IndexStatus;
import com.devassist.rag.IndexStatusService;
import com.devassist.rag.RagAnswerResponse;
import com.devassist.rag.RagQueryService;

@Service
public class EvaluationService {

	private final EvalCorpusLocator locator;
	private final EvalDataset dataset;
	private final DocumentService documentService;
	private final IndexStatusService indexStatusService;
	private final RagQueryService ragQueryService;
	private final JudgeService judgeService;
	private final EvalProperties properties;

	public EvaluationService(EvalCorpusLocator locator, EvalDataset dataset, DocumentService documentService,
			IndexStatusService indexStatusService, RagQueryService ragQueryService, JudgeService judgeService,
			EvalProperties properties) {
		this.locator = locator;
		this.dataset = dataset;
		this.documentService = documentService;
		this.indexStatusService = indexStatusService;
		this.ragQueryService = ragQueryService;
		this.judgeService = judgeService;
		this.properties = properties;
	}

	public EvalReportResponse runEvaluation() {
		String projectId = locator.findProjectId().orElseThrow(EvalCorpusNotReadyException::new);
		assertCorpusIsIndexed(projectId);

		List<EvalResultEntry> entries = new ArrayList<>();
		for (EvalQuestion question : dataset.questions()) {
			entries.add(evaluateOne(projectId, question));
		}
		return new EvalReportResponse(entries, summarize(entries));
	}

	// BR-06: zero documents, or documents that exist but never finished
	// indexing (e.g. Ollama was down when EvalCorpusSeeder ran), both mean
	// there is nothing real to query against.
	private void assertCorpusIsIndexed(String projectId) {
		List<Document> documents = documentService.findByProject(projectId);
		boolean anyIndexed = documents.stream()
				.map(doc -> indexStatusService.get(doc.id()))
				.anyMatch(status -> status.isPresent() && status.get().state() == IndexStatus.State.INDEXED);
		if (!anyIndexed) {
			throw new EvalCorpusNotReadyException();
		}
	}

	private EvalResultEntry evaluateOne(String projectId, EvalQuestion question) {
		RagAnswerResponse response;
		try {
			response = ragQueryService.answer(projectId, question.question());
		}
		catch (RuntimeException ex) {
			// BR-05: one failing question must never abort the whole run.
			return new EvalResultEntry(question.question(), question.answerable(), null, false, ex.getMessage());
		}

		boolean answered = response.status() == RagAnswerResponse.Status.ANSWERED;
		EvaluationScore score = answered ? judgeSafely(question, response) : programmaticScore(question, response);

		RagAnswerResponse scored = new RagAnswerResponse(response.question(), response.answer(), response.status(),
				response.sources(), response.latencyMs(), score);
		boolean matched = answered == question.answerable();
		return new EvalResultEntry(question.question(), question.answerable(), scored, matched, null);
	}

	// BR-04: JudgeService's own parse() already turns a malformed RESPONSE into
	// UNSCORABLE internally, but judgeAnswered() can still throw before that -
	// its lazy Gemini bean resolution can fail (BeanCreationException, same as
	// GenerationService), or the live call itself can fail (ApiException /
	// NonTransientAiException). Either must degrade to UNSCORABLE here too,
	// not propagate and abort the whole run.
	private EvaluationScore judgeSafely(EvalQuestion question, RagAnswerResponse response) {
		try {
			return judgeService.judgeAnswered(question.question(), response.answer(), response.sources());
		}
		catch (RuntimeException ex) {
			return new EvaluationScore(null, null, null, "Judge call failed: " + ex.getMessage(),
					EvaluationScore.Method.UNSCORABLE);
		}
	}

	private EvaluationScore programmaticScore(EvalQuestion question, RagAnswerResponse response) {
		return new EvaluationScore(null, null, !question.answerable(),
				"Question expected answerable=" + question.answerable() + "; system returned " + response.status(),
				EvaluationScore.Method.PROGRAMMATIC);
	}

	private EvalSummary summarize(List<EvalResultEntry> entries) {
		List<EvalResultEntry> withResponse = entries.stream().filter(e -> e.response() != null).toList();

		// answerableCount/unanswerableCount describe the DATASET, not the
		// outcome - a question that failed outright (no response) still counts
		// toward whichever bucket it was drawn from, matching the spec's
		// response example where these two always sum to totalQuestions.
		int answerableCount = (int) entries.stream().filter(EvalResultEntry::expectedAnswerable).count();
		int unanswerableCount = entries.size() - answerableCount;

		List<EvalResultEntry> answerableWithResponse = withResponse.stream()
				.filter(EvalResultEntry::expectedAnswerable)
				.toList();
		List<EvalResultEntry> judged = answerableWithResponse.stream()
				.filter(e -> e.response().evaluation().method() == EvaluationScore.Method.JUDGED)
				.toList();
		List<EvalResultEntry> unanswerableWithResponse = withResponse.stream()
				.filter(entry -> !entry.expectedAnswerable())
				.toList();

		// BR-04 + the spec's error table: a query-level failure (no response
		// at all) AND a judge-level failure (response exists, but its
		// evaluation is UNSCORABLE) both count as failed - an inconclusive
		// run must not look clean just because RagQueryService itself succeeded.
		long unscorableCount = answerableWithResponse.size() - judged.size();
		int failed = (entries.size() - withResponse.size()) + (int) unscorableCount;

		// BR-07: faithfulness/completeness only over ANSWERED+JUDGED results.
		OptionalDouble avgFaithfulness = judged.stream()
				.mapToInt(e -> e.response().evaluation().faithfulness())
				.average();
		OptionalDouble avgCompleteness = judged.stream()
				.mapToInt(e -> e.response().evaluation().completeness())
				.average();
		OptionalDouble correctlyDeclinedRate = unanswerableWithResponse.isEmpty() ? OptionalDouble.empty()
				: OptionalDouble.of(unanswerableWithResponse.stream()
						.filter(e -> Boolean.TRUE.equals(e.response().evaluation().correctlyDeclined()))
						.count() / (double) unanswerableWithResponse.size());

		// BR-08: an inconclusive run (any failure) is not a passing one,
		// regardless of how good the scores that DID complete look.
		boolean passed = failed == 0
				&& avgFaithfulness.orElse(0) >= properties.minFaithfulness()
				&& avgCompleteness.orElse(0) >= properties.minCompleteness()
				&& correctlyDeclinedRate.orElse(0) >= properties.minCorrectlyDeclinedRate();

		return new EvalSummary(entries.size(), answerableCount, unanswerableCount,
				avgFaithfulness.isPresent() ? avgFaithfulness.getAsDouble() : null,
				avgCompleteness.isPresent() ? avgCompleteness.getAsDouble() : null,
				correctlyDeclinedRate.isPresent() ? correctlyDeclinedRate.getAsDouble() : null, failed, passed);
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=EvaluationServiceTest`
Expected: PASS — all 10 tests.

Run: `./mvnw test`
Expected: PASS, 191 tests total, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/eval/EvaluationService.java src/main/java/com/devassist/eval/EvalResultEntry.java src/main/java/com/devassist/eval/EvalSummary.java src/main/java/com/devassist/eval/EvalReportResponse.java src/test/java/com/devassist/eval/EvaluationServiceTest.java
git commit -m "feat: orchestrate the two-track evaluation run with per-question isolation"
```

---

### Task 7: `EvalController`

Replaces the Task 2 placeholder with the real endpoint.

**Files:**
- Modify: `src/main/java/com/devassist/eval/EvalController.java`
- Test: `src/test/java/com/devassist/eval/EvalControllerTest.java`

**Interfaces:**
- Consumes: `EvaluationService.runEvaluation()` → `EvalReportResponse`.
- Produces: `POST /api/eval/run`.

- [ ] **Step 1: Write the failing tests**

```java
package com.devassist.eval;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.rag.EvaluationScore;
import com.devassist.rag.RagAnswerResponse;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvalController.class)
class EvalControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EvaluationService evaluationService;

	@Test
	void returnsTheReportOnSuccess() throws Exception {
		RagAnswerResponse response = new RagAnswerResponse("q1", "30 days. [1]", RagAnswerResponse.Status.ANSWERED,
				List.of(), 100, new EvaluationScore(5, 5, null, "ok", EvaluationScore.Method.JUDGED));
		EvalResultEntry entry = new EvalResultEntry("q1", true, response, true, null);
		EvalSummary summary = new EvalSummary(1, 1, 0, 5.0, 5.0, null, 0, true);
		when(evaluationService.runEvaluation()).thenReturn(new EvalReportResponse(List.of(entry), summary));

		mockMvc.perform(post("/api/eval/run"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.passed").value(true))
				.andExpect(jsonPath("$.results[0].question").value("q1"))
				.andExpect(jsonPath("$.results[0].response.evaluation.faithfulness").value(5));
	}

	@Test
	void returnsServiceUnavailableWhenCorpusIsNotReady() throws Exception {
		when(evaluationService.runEvaluation()).thenThrow(new EvalCorpusNotReadyException());

		mockMvc.perform(post("/api/eval/run"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503));
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=EvalControllerTest`
Expected: FAIL — the placeholder `EvalController` has no `/api/eval/run`
mapping, so both requests 404.

- [ ] **Step 3: Implement the real `EvalController`**

Replace the Task 2 placeholder body entirely:

```java
package com.devassist.eval;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

	private final EvaluationService evaluationService;

	public EvalController(EvaluationService evaluationService) {
		this.evaluationService = evaluationService;
	}

	@PostMapping("/run")
	public ResponseEntity<EvalReportResponse> run() {
		return ResponseEntity.ok(evaluationService.runEvaluation());
	}
}
```

The second test in this file (`returnsServiceUnavailableWhenCorpusIsNotReady`)
also re-verifies Task 2's `EvalExceptionHandler` now that a real
`EvalController` exists and `assignableTypes = EvalController.class` has a
real target to match against — Task 2's own test proved the handler method
in isolation; this is the first test proving the two are wired together
correctly end-to-end through Spring's advice matching.

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=EvalControllerTest`
Expected: PASS — both tests.

Run: `./mvnw test`
Expected: PASS, 193 tests total, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/eval/EvalController.java src/test/java/com/devassist/eval/EvalControllerTest.java
git commit -m "feat: expose POST /api/eval/run"
```

---

### Task 8: End-to-end verification

Everything above is unit-tested with mocks. This task proves the real
pipeline — real Ollama, real Gemini, real startup seeding — actually
produces a sensible report.

**Files:**
- Create: `http/eval-api.http`

**Interfaces:**
- Consumes: every previous task.
- Produces: nothing — a manual verification gate, same role as the RAG
  core plan's final task.

- [ ] **Step 1: Confirm supporting services**

```bash
curl -s http://localhost:11434/api/tags | grep nomic-embed-text
echo "${GEMINI_API_KEY:+set}${GEMINI_API_KEY:-NOT SET}"
```
Expected: `nomic-embed-text` present; key reported `set`. If the key is
unset, Task 6's judge calls will 503 (`ApiException`/`BeanCreationException`
via `RagExceptionHandler`, not `EvalExceptionHandler` — that path is
already proven by the RAG core's own end-to-end task) — still start the app
and confirm seeding + the 503-when-corpus-not-ready path (Step 4 below), and
mark the full-report checks unverified with the reason, exactly as the RAG
core plan's Task 13 did for the same constraint.

- [ ] **Step 2: Start the application and confirm seeding**

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8095
```
Watch the startup log for `Started DevassistApplication` with no warning
from `EvalCorpusSeeder`. Then:
```bash
curl -s http://localhost:8095/api/projects | grep "RAG Evaluation Corpus"
```
Expected: the eval project is present without you having created it.

- [ ] **Step 3: Create the request file**

```
### Run the evaluation
POST http://localhost:8095/api/eval/run

> {%
    client.test("200 OK", () => response.status === 200);
    client.test("8 questions", () => response.body.results.length === 8);
    client.test("Has a pass/fail verdict", () => typeof response.body.summary.passed === "boolean");
%}

### Run it again - proves startup seeding is idempotent, not appended
POST http://localhost:8095/api/eval/run

> {%
    client.test("Still 8 questions, not 16", () => response.body.results.length === 8);
%}
```

- [ ] **Step 4: Run both requests and inspect the report by hand**

Confirm: all 6 answerable questions show `method: "JUDGED"` with numeric
scores; both unanswerable questions show `method: "PROGRAMMATIC"` and
`correctlyDeclined: true`; `summary.failedQuestions` is `0`;
`outcomeMatchedExpectation` is `true` for every entry (if any is `false`,
the corpus content and the dataset's expectations have drifted — check
`src/main/resources/eval/*.txt` against `eval-dataset.json`).

- [ ] **Step 5: Stop the application, commit**

```bash
git add http/eval-api.http
git commit -m "test: add end-to-end eval request file"
```

---

## Completion Checklist

- [ ] `./mvnw test` passes; the 165 tests present before this plan are still
  among them, none deleted or weakened.
- [ ] `EvaluationScore` lives in `com.devassist.rag`, not `com.devassist.eval`
  (Correction 1).
- [ ] `EvalExceptionHandler` is its own advice class in `com.devassist.eval`,
  not an addition to `RagExceptionHandler` (Correction 2).
- [ ] No file under `com.devassist.rag`, `com.devassist.document`, or
  `com.devassist.project` was modified except `RagAnswerResponse.java`.
- [ ] `EvalCorpusSeeder.run()` cannot propagate an exception under any
  tested failure condition (BR-01).
- [ ] `EvaluationService` calls the real `RagQueryService.answer(...)`, not
  a reimplementation (BR-02).
- [ ] No secret appears in any committed file.
