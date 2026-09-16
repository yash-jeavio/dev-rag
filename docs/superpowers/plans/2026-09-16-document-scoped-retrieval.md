# Document-Scoped Retrieval Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a query narrow to one or more specific documents within a
project instead of always searching the whole project's vector store.

**Architecture:** A pure retrieval-filter change. `RagQueryRequest` gains
an optional `documentIds` field; `RetrievalService` builds a compound
`projectId AND documentId IN [...]` filter when it's non-empty, or today's
exact `projectId`-only filter when it's empty. No new classes, no changes
to chunking/embedding/indexing, no changes to anything downstream of
retrieval (context building, generation, citations, the eval harness). A
checkbox per document in the existing manual test UI lets a user select
which documents to scope a question to.

**Tech Stack:** Java 17, Spring Boot 4.1.1, Spring AI 2.0.1
(`FilterExpressionBuilder`, already on the classpath), Maven Wrapper.

**Spec:** `specs/document-scoped-retrieval.md`

## Global Constraints

- Java 17 / Spring Boot 4.1.1 fixed — no framework changes.
- Maven Wrapper only (`./mvnw`), never a global `mvn`.
- Constructor injection only; no field or setter injection.
- Immutable records for DTOs.
- No new dependencies.
- Tests must pass with no Ollama, no Gemini/OpenAI key, no network access.
- All existing tests (209 on `main`) must all still pass. Do not delete or
  weaken any existing test.
- Comment only non-obvious "why", never "what".
- No new files. Every change is to an existing, already-reviewed class:
  `RagQueryRequest`, `RetrievalService`, `RagQueryService`,
  `RagQueryController` (all in `com.devassist.rag`). Nothing in
  `com.devassist.document`, `com.devassist.project`, or `com.devassist.eval`
  is modified — `EvaluationService`'s existing call to the 2-arg
  `RagQueryService.answer(projectId, question)` must keep working
  unchanged.
- `RetrievalService.retrieve(...)` has exactly one caller
  (`RagQueryService`, same package) — its signature changes outright to
  take a required third parameter, not an overload. Every existing test
  call site is updated to pass `List.of()` for the whole-project case.
- `RagQueryService.answer(...)` keeps its existing 2-arg signature as a
  public method — it becomes a thin delegate to a new 3-arg overload, so
  no external caller (in particular `EvaluationService`) needs to change.

---

### Task 1: `RagQueryRequest` gains `documentIds`

**Files:**
- Modify: `src/main/java/com/devassist/rag/RagQueryRequest.java`
- Test: `src/test/java/com/devassist/rag/RagQueryRequestTest.java` (new file)

**Interfaces:**
- Produces: `RagQueryRequest(String question, List<String> documentIds)` —
  `documentIds` is never `null` after construction (a `null` argument
  normalizes to `List.of()`). Consumed by Task 4 (`RagQueryController`).

- [ ] **Step 1: Write the failing test**

```java
package com.devassist.rag;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryRequestTest {

	@Test
	void normalizesANullDocumentIdsToAnEmptyList() {
		RagQueryRequest request = new RagQueryRequest("question", null);

		assertThat(request.documentIds()).isEmpty();
	}

	@Test
	void preservesAProvidedDocumentIdsList() {
		RagQueryRequest request = new RagQueryRequest("question", List.of("doc-1", "doc-2"));

		assertThat(request.documentIds()).containsExactly("doc-1", "doc-2");
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=RagQueryRequestTest`
Expected: FAIL — compile error, `RagQueryRequest`'s current constructor
takes only one argument (`question`).

- [ ] **Step 3: Update `RagQueryRequest`**

Replace the entire file:

```java
package com.devassist.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagQueryRequest(@NotBlank @Size(max = 1000) String question, List<String> documentIds) {

	// Jackson passes null when the field is omitted from the request body -
	// normalizing here means RetrievalService and everything else can just
	// check isEmpty(), never null, for "search the whole project" (BR-01,
	// specs/document-scoped-retrieval.md).
	public RagQueryRequest {
		documentIds = documentIds == null ? List.of() : documentIds;
	}
}
```

- [ ] **Step 4: Run the test to verify it passes, then the full suite**

Run: `./mvnw test -Dtest=RagQueryRequestTest`
Expected: PASS (2/2).

Run: `./mvnw test`
Expected: PASS. `RagQueryController`'s existing tests still send
`{"question": "..."}` with no `documentIds` field — Jackson passes `null`
for the missing constructor argument, which the compact constructor
normalizes, so nothing downstream breaks. `209 + 2` tests total.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/rag/RagQueryRequest.java \
  src/test/java/com/devassist/rag/RagQueryRequestTest.java
git commit -m "feat: add an optional documentIds field to RagQueryRequest"
```

---

### Task 2: `RetrievalService` builds a compound filter

**Files:**
- Modify: `src/main/java/com/devassist/rag/RetrievalService.java`
- Modify: `src/test/java/com/devassist/rag/RetrievalServiceTest.java`

**Interfaces:**
- Consumes: nothing new from Task 1 (this task is independent of it — both
  build toward Task 3).
- Produces: `RetrievalService.retrieve(String projectId, String question, List<String> documentIds)`
  → `List<org.springframework.ai.document.Document>`. Consumed by Task 3
  (`RagQueryService`).

- [ ] **Step 1: Update the existing tests' call sites**

`RetrievalServiceTest.java` currently has 6 tests, every one calling
`service.retrieve("proj-1", "...")` with 2 arguments (some via a second
`otherService` instance). Add a third argument, `List.of()`, to **every**
call site in the file. For example, the first test's call:

```java
service.retrieve("proj-1", "any question");
```

becomes:

```java
service.retrieve("proj-1", "any question", List.of());
```

Apply this exact change (append `, List.of()`) to all 6 occurrences in the
file — every `.retrieve(` call, including the one on `otherService` in
`topKAndThresholdAreNotHardcodedButReadFromProperties`. Every existing
assertion in the file stays completely unchanged; this step only changes
how `retrieve` is called, not what any test checks.

- [ ] **Step 2: Write the two new failing tests**

Add these to the same file:

```java
	@Test
	void narrowsToTheGivenDocumentsWhenDocumentIdsIsNonEmpty() {
		service.retrieve("proj-1", "any question", List.of("doc-1", "doc-2"));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		String filter = captor.getValue().getFilterExpression().toString();
		assertThat(filter).contains("proj-1");
		assertThat(filter).contains("doc-1");
		assertThat(filter).contains("doc-2");
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=RetrievalServiceTest`
Expected: FAIL — compile error, `retrieve` currently takes 2 arguments,
not 3.

- [ ] **Step 4: Update `RetrievalService`**

Replace the entire file:

```java
package com.devassist.rag;

import java.util.List;

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

	public RetrievalService(VectorStore vectorStore, RagProperties properties) {
		this.vectorStore = vectorStore;
		this.properties = properties;
	}

	public List<Document> retrieve(String projectId, String question, List<String> documentIds) {
		SearchRequest request = SearchRequest.builder()
				.query(question)
				.topK(properties.topK())
				.similarityThreshold(properties.similarityThreshold())
				.filterExpression(buildFilter(projectId, documentIds))
				.build();

		List<Document> results = vectorStore.similaritySearch(request);
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

- [ ] **Step 5: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=RetrievalServiceTest`
Expected: PASS (8/8 — 6 migrated + 2 new).

Run: `./mvnw test`
Expected: PASS. `211 + 2 = 213` tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/rag/RetrievalService.java \
  src/test/java/com/devassist/rag/RetrievalServiceTest.java
git commit -m "feat: scope retrieval to specific documents when requested"
```

---

### Task 3: `RagQueryService` gains a 3-arg `answer(...)` overload

**Files:**
- Modify: `src/main/java/com/devassist/rag/RagQueryService.java`
- Modify: `src/test/java/com/devassist/rag/RagQueryServiceTest.java`

**Interfaces:**
- Consumes: `RetrievalService.retrieve(String, String, List<String>)`
  (Task 2).
- Produces: `RagQueryService.answer(String projectId, String question, List<String> documentIds)`
  → `RagAnswerResponse`. The existing `answer(String, String)` keeps
  working, delegating to this with `List.of()`. Consumed by Task 4
  (`RagQueryController`).

- [ ] **Step 1: Update the existing tests' mock stubs**

`RagQueryServiceTest.java` mocks `RetrievalService` and currently stubs it
with 2-arg matchers in both existing tests:

```java
when(retrievalService.retrieve(anyString(), anyString())).thenReturn(List.of());
```

and

```java
when(retrievalService.retrieve(anyString(), anyString())).thenReturn(List.of(
```

Change both occurrences to add a third matcher, `anyList()`:

```java
when(retrievalService.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of());
```

```java
when(retrievalService.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of(
```

Add `import static org.mockito.ArgumentMatchers.anyList;` to the imports.
Every existing assertion in the file stays completely unchanged.

- [ ] **Step 2: Write the two new failing tests**

Add these to the same file:

```java
	@Test
	void passesDocumentIdsThroughToRetrieval() {
		when(retrievalService.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of());

		service.answer("proj-1", "question", List.of("doc-1", "doc-2"));

		verify(retrievalService).retrieve("proj-1", "question", List.of("doc-1", "doc-2"));
	}

	@Test
	void theTwoArgOverloadDelegatesWithAnEmptyDocumentIdsList() {
		when(retrievalService.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of());

		service.answer("proj-1", "question");

		verify(retrievalService).retrieve("proj-1", "question", List.of());
	}
```

Add `import static org.mockito.Mockito.verify;` if not already present
(check the file's current imports first — it may already import `verify`
for other purposes).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=RagQueryServiceTest`
Expected: FAIL — compile error, `answer` has no 3-arg overload yet, and
`retrievalService.retrieve` has no 3-arg version to stub against.

- [ ] **Step 4: Update `RagQueryService`**

Replace the `answer` method (and only this method — everything else in the
file is unchanged):

```java
	public RagAnswerResponse answer(String projectId, String question) {
		return answer(projectId, question, List.of());
	}

	public RagAnswerResponse answer(String projectId, String question, List<String> documentIds) {
		long startedAt = System.currentTimeMillis();
		projectService.findById(projectId);

		List<Document> chunks = retrievalService.retrieve(projectId, question, documentIds);
		if (chunks.isEmpty()) {
			// BR-08: no model call at all — nothing retrieved means nothing to ground on.
			return RagAnswerResponse.of(question, NO_CONTEXT_ANSWER,
					RagAnswerResponse.Status.INSUFFICIENT_CONTEXT, List.of(),
					System.currentTimeMillis() - startedAt);
		}

		String answer = generationService.generate(contextBuilder.buildContext(chunks), question);
		return RagAnswerResponse.of(question, answer, RagAnswerResponse.Status.ANSWERED,
				contextBuilder.toSources(chunks, answer), System.currentTimeMillis() - startedAt);
	}
```

- [ ] **Step 5: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=RagQueryServiceTest`
Expected: PASS (4/4 — 2 migrated + 2 new).

Run: `./mvnw test`
Expected: PASS. `213 + 2 = 215` tests total.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/rag/RagQueryService.java \
  src/test/java/com/devassist/rag/RagQueryServiceTest.java
git commit -m "feat: add a documentIds-aware overload of RagQueryService.answer"
```

---

### Task 4: `RagQueryController` passes `documentIds` through

**Files:**
- Modify: `src/main/java/com/devassist/rag/RagQueryController.java`
- Modify: `src/test/java/com/devassist/rag/RagQueryControllerTest.java`

**Interfaces:**
- Consumes: `RagQueryRequest.documentIds()` (Task 1),
  `RagQueryService.answer(String, String, List<String>)` (Task 3).
- Produces: nothing new — this is the last task, wiring everything
  together end-to-end.

`RagQueryControllerTest.java` has 6 existing tests, and **every one** of
them stubs `queryService.answer(anyString(), anyString())` with 2-arg
matchers. Once the controller calls the 3-arg `answer(...)`, all 6 stubs
must be migrated in the same step, or every one of those tests breaks
(Mockito returns `null` for an unstubbed method call, not the stubbed
value).

- [ ] **Step 1: Update all 6 existing stubs and write the two new failing tests**

In `RagQueryControllerTest.java`, change every occurrence of
`queryService.answer(anyString(), anyString())` to
`queryService.answer(anyString(), anyString(), anyList())`. This appears
in exactly 6 places: `returnsAnswerWithSources`,
`returnsNotFoundForUnknownProject`,
`returnsServiceUnavailableWhenTheAiProviderFails`,
`returnsServiceUnavailableWhenTheChatModelBeanFailsToBuild`,
`returnsServiceUnavailableWithTheRootCauseWhenGoogleRejectsTheModel`, and
`returnsServiceUnavailableWhenOpenAiRejectsTheRequest`. Add
`import static org.mockito.ArgumentMatchers.anyList;` to the imports.
`rejectsBlankQuestion` has no stub at all — leave it untouched.

Every existing assertion in every test stays completely unchanged; this
step only changes how the mock is stubbed.

Then add these two new tests to the same file:

```java
	@Test
	void passesDocumentIdsFromTheRequestBodyToTheService() throws Exception {
		when(queryService.answer(anyString(), anyString(), anyList())).thenReturn(
				RagAnswerResponse.of("q", "answer [1]", RagAnswerResponse.Status.ANSWERED,
						List.of(new SourceReference("doc-1", "a.md", 0, 0.82, true, "text")), 12));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\",\"documentIds\":[\"doc-1\",\"doc-3\"]}"))
				.andExpect(status().isOk());

		verify(queryService).answer("proj-1", "q", List.of("doc-1", "doc-3"));
	}

	@Test
	void omittingDocumentIdsStillSearchesTheWholeProject() throws Exception {
		when(queryService.answer(anyString(), anyString(), anyList())).thenReturn(
				RagAnswerResponse.of("q", "answer [1]", RagAnswerResponse.Status.ANSWERED,
						List.of(new SourceReference("doc-1", "a.md", 0, 0.82, true, "text")), 12));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isOk());

		verify(queryService).answer("proj-1", "q", List.of());
	}
```

Add `import static org.mockito.Mockito.verify;` if not already present
(check the file's current imports first).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=RagQueryControllerTest`
Expected: FAIL — compile error, `queryService.answer` has no 3-arg
overload to stub/verify against yet (it does, from Task 3 — but the
*controller* itself still calls the 2-arg version, so `verify(queryService).answer("proj-1", "q", List.of())`
finds no matching invocation and the new tests fail with a Mockito
verification failure, not a compile error, once Task 3 is already done).
Confirm the two new tests specifically fail before proceeding.

- [ ] **Step 3: Update `RagQueryController`**

Change the one line in the `query` method:

```java
	@PostMapping
	public ResponseEntity<RagAnswerResponse> query(@PathVariable String projectId,
			@Valid @RequestBody RagQueryRequest request) {
		return ResponseEntity.ok(queryService.answer(projectId, request.question(), request.documentIds()));
	}
```

(This replaces `queryService.answer(projectId, request.question())` —
everything else in the file, including the class-level annotations and
constructor, is unchanged.)

- [ ] **Step 4: Run the tests to verify they pass, then the full suite**

Run: `./mvnw test -Dtest=RagQueryControllerTest`
Expected: PASS (8/8 — 6 migrated + 2 new).

Run: `./mvnw test`
Expected: PASS. `215 + 2 = 217` tests total, 0 failures, 0 errors.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/rag/RagQueryController.java \
  src/test/java/com/devassist/rag/RagQueryControllerTest.java
git commit -m "feat: wire documentIds from the query request through to retrieval"
```

---

### Task 5: UI document checkboxes

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `POST /api/projects/{projectId}/query` with an optional
  `documentIds` field (Task 4).
- Produces: nothing new — this is the last task, wiring the UI to the
  backend work done in Tasks 1-4.

No automated test — `index.html` has no test harness in this project
(the same pattern as the chat-provider-switching plan's own UI task).
Verified manually in Step 3.

- [ ] **Step 1: Add a checkbox to each document row**

In the `refreshDocs()` function, find this block:

```javascript
			row.innerHTML = `
				<div>
					<span class="title">${escapeHtml(doc.title)}</span>
					<span class="meta">${doc.sourceType} &middot; ${status.chunkCount ?? 0} chunk(s)</span>
					${status.reason ? `<span class="meta error-text">${escapeHtml(status.reason)}</span>` : ''}
				</div>
				<div class="doc-actions">
					<span class="badge ${status.status}">${status.status}</span>
					<button class="secondary" data-summarize="${doc.id}">Summarize</button>
				</div>`;
```

and add a checkbox at the start of the row, immediately before the title
span:

```javascript
			row.innerHTML = `
				<div>
					<input type="checkbox" class="doc-select" value="${doc.id}" style="margin-right:8px;">
					<span class="title">${escapeHtml(doc.title)}</span>
					<span class="meta">${doc.sourceType} &middot; ${status.chunkCount ?? 0} chunk(s)</span>
					${status.reason ? `<span class="meta error-text">${escapeHtml(status.reason)}</span>` : ''}
				</div>
				<div class="doc-actions">
					<span class="badge ${status.status}">${status.status}</span>
					<button class="secondary" data-summarize="${doc.id}">Summarize</button>
				</div>`;
```

- [ ] **Step 2: Include checked document IDs in the ask-a-question request**

In the `askBtn` click handler, find:

```javascript
		const question = document.getElementById('questionInput').value.trim();
		const box = document.getElementById('answerBox');
		if (!question) return;

		box.innerHTML = '<p class="muted"><span class="spinner"></span>Retrieving relevant chunks, then asking Gemini…</p>';
		try {
			const res = await fetch(`/api/projects/${projectId}/query`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ question })
			});
```

and change it to collect the checked document IDs and include them:

```javascript
		const question = document.getElementById('questionInput').value.trim();
		const box = document.getElementById('answerBox');
		if (!question) return;

		const documentIds = Array.from(document.querySelectorAll('.doc-select:checked')).map(cb => cb.value);

		box.innerHTML = '<p class="muted"><span class="spinner"></span>Retrieving relevant chunks, then asking Gemini…</p>';
		try {
			const res = await fetch(`/api/projects/${projectId}/query`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ question, documentIds })
			});
```

An empty `documentIds` array (nothing checked) and an omitted field are
handled identically by `RagQueryRequest` (Task 1) — both normalize to
"search the whole project" — so always sending the array, even when
empty, needs no special-casing here.

- [ ] **Step 3: Manual verification**

```bash
./mvnw spring-boot:run
```

1. Open the app in a browser, upload two or more documents with clearly
   different content.
2. Ask a question with nothing checked — confirm it answers from whatever
   document actually contains the relevant information (today's
   whole-project behavior, unchanged).
3. Check exactly one document's checkbox (one that does NOT contain the
   answer to your next question) and ask that same question again —
   confirm the response is now `INSUFFICIENT_CONTEXT`, since retrieval is
   now scoped to only the checked document.
4. Check the document that DOES contain the answer and ask again — confirm
   it answers normally, with a source pointing at that document.

- [ ] **Step 4: Run the full suite one more time**

Run: `./mvnw test`
Expected: PASS, 217 tests, 0 failures, 0 errors (unaffected by this task —
`index.html` has no automated coverage).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add document selection checkboxes to the manual test UI"
```

---

## Completion Checklist

- [ ] `./mvnw test` passes; the 209 tests present before this plan are
  still among them, none deleted or weakened.
- [ ] Omitting `documentIds` (or sending `null`/`[]`) produces the exact
  same behavior as before this plan (BR-01).
- [ ] A non-empty `documentIds` narrows retrieval to the intersection of
  project membership and the selected documents — never across a project
  boundary (BR-02) — proven by `RetrievalServiceTest`'s compound-filter
  test.
- [ ] `RagQueryService.answer(String, String)` (2-arg) still exists and
  still works unchanged — `EvaluationService`'s existing call was never
  touched (BR-05).
- [ ] No new files, no new dependencies, nothing in
  `com.devassist.document`/`com.devassist.project`/`com.devassist.eval`
  modified.
- [ ] The UI's document list lets a user select one or more documents, and
  a query scoped to a selection that doesn't contain the answer correctly
  returns `INSUFFICIENT_CONTEXT` (manually verified in Task 5).
- [ ] No secret appears in any committed file.
