# Feature Specification: Document-Scoped Retrieval

## 1. Goal

Let a caller narrow a question to one or more specific documents within a
project, instead of always searching the whole project's vector store.
Motivated by projects with many documents (100+), where a user often knows
which document their question concerns and wants to search only that one
— both for accuracy (avoiding irrelevant chunks from unrelated documents
competing in the same top-K) and for speed of finding the right answer.

Out of scope: persisting a "default" document selection per project or per
user; any change to how documents are chunked, embedded, or indexed;
document-scoping for the eval harness's fixed question set (it always
searches the whole eval corpus, unchanged).

## 2. Why This Is Purely a Retrieval-Filter Change

Every chunk written to the vector store already carries a `documentId` in
its metadata (set by `DocumentIndexer.index(...)`, confirmed by reading
its source) alongside `projectId`, `title`, `sourceType`, and
`chunkIndex`. Project-scoping already works by attaching a
`FilterExpressionBuilder` filter (`eq("projectId", projectId)`) to the
`SearchRequest` passed to `VectorStore.similaritySearch(...)`
(`RetrievalService.retrieve(...)`, confirmed by reading its source).
Document-scoping is the same mechanism, one level narrower — no new
storage, no new metadata, no change to indexing at all. Spring AI's
`FilterExpressionBuilder` already provides `in(String key, List<Object>
values)` (confirmed by reading its source), which is what "search these N
documents" compiles to.

Because the compound filter is always `projectId AND (optionally)
documentId IN [...]`, a `documentId` that doesn't belong to the requesting
project can never leak chunks from another project — the `projectId`
clause still has to hold regardless. Document-scoping can only narrow
*within* a project; it can never widen *across* one. This is the same
"never trust client input to bypass project isolation" property
`specs/rag-core.md`'s BR-06 already established for project-scoping.

## 3. Changed Components — `com.devassist.rag`

No new files. Every change is to an existing class already reviewed and
tested as part of the RAG core.

| File | Change |
|---|---|
| `RagQueryRequest` | Gains `List<String> documentIds`, defaulting to an empty list when omitted from the request body (a `null` from Jackson is normalized to `List.of()` in a compact constructor — nothing downstream needs to null-check). |
| `RagQueryService` | `answer(String projectId, String question)` (existing, 2-arg) becomes a thin delegate to a new `answer(String projectId, String question, List<String> documentIds)` (3-arg), called with `List.of()`. Every existing caller (including `EvaluationService`, across the `eval → rag` dependency) keeps working with zero changes. |
| `RetrievalService` | `retrieve(String projectId, String question)` gains a required third parameter, `List<String> documentIds`. This service has exactly one caller (`RagQueryService`, both in `com.devassist.rag`), so the signature changes outright rather than gaining an overload — existing tests are updated to pass `List.of()` for the whole-project case. |
| `RagQueryController` | No signature change — `request.documentIds()` flows through alongside `request.question()` automatically once `RagQueryRequest` carries it. |

## 4. API

`POST /api/projects/{projectId}/query`

```json
{ "question": "What's the refund window?", "documentIds": ["doc-1", "doc-3"] }
```

Omitting `documentIds` (or sending `[]`, or sending `null`) searches the
whole project — identical to every request this endpoint accepts today.
This is a backward-compatible addition: no existing client of this
endpoint (the manual test UI, the `.http` files, the eval harness's
underlying call path) needs to change to keep working exactly as before.

## 5. UI Change

`src/main/resources/static/index.html`'s document list (currently
read-only aside from its per-document "Summarize" button) gains a
checkbox per document. The question request includes whichever document
IDs are checked; none checked means the request omits `documentIds`
entirely (equivalent to searching the whole project).

## 6. Business Rules

- **BR-01**: When `documentIds` is empty or absent, retrieval scope is
  unchanged from today — the whole project, exactly as `specs/rag-core.md`
  BR-06 already specifies.
- **BR-02**: When `documentIds` is non-empty, retrieval is scoped to the
  intersection of project membership and the selected document IDs. The
  compound filter (`projectId AND documentId IN [...]`) makes it
  structurally impossible for a selection to retrieve chunks from a
  document outside the requesting project — narrowing is always *within*
  the project, never *across* it.
- **BR-03**: A `documentId` that matches no indexed chunk (a typo, an ID
  from a different project, a document not yet finished indexing, or one
  that was deleted) is not an error. It simply contributes no chunks to
  the search — the same outcome as any other over-narrow query. No
  existence-check against the document list is performed before
  searching.
- **BR-04**: If the scoped search returns zero chunks — whether because
  every selected document has no indexed content yet, or because nothing
  in the selection matches the question — the existing
  `INSUFFICIENT_CONTEXT` short-circuit applies exactly as it does for an
  empty whole-project search today (`specs/rag-core.md` BR-08): no model
  call is made.
- **BR-05**: `RagQueryService.answer(String projectId, String question)`
  (the existing 2-arg method) continues to work unchanged by delegating to
  the new 3-arg overload with an empty list. No caller outside
  `com.devassist.rag` — in particular, nothing in `com.devassist.eval` —
  needs to change.

## 7. Error Conditions

| Condition | Status | Handling |
|---|---|---|
| `documentIds` omitted, `null`, or `[]` | 200 (whole-project search, as today) | Normalized to `List.of()` in `RagQueryRequest`'s compact constructor |
| One or more `documentIds` provided, none match any indexed chunk | 200, `INSUFFICIENT_CONTEXT` | Same short-circuit as an empty whole-project result (BR-04) — no new error path |
| A provided `documentId` doesn't exist or belongs to another project | 200 (silently contributes nothing) | BR-03 — not validated, not an error |

No new exception type, no new `RagExceptionHandler` entry — this feature
introduces no new failure mode beyond what whole-project retrieval already
has.

## 8. Non-Functional Requirements

- No new dependencies — `FilterExpressionBuilder.in(...)` is already part
  of `spring-ai-vector-store`, already on the classpath.
- No change to chunking, embedding, or indexing — every change is to the
  retrieval-time filter only.
- No change to `ContextBuilder`, `GenerationService`, citation logic, or
  the eval harness — none of them are aware of *why* a chunk was
  retrieved, only that it was, so nothing downstream of retrieval needs to
  change.
- Immutable records; constructor injection — no deviation from existing
  conventions.

## 9. Testing

Tests must pass with no Ollama, no Gemini/OpenAI key, and no network
access — same standing rule as the rest of this project.

- **`RetrievalServiceTest`** (existing, extended): existing whole-project
  cases are unchanged in behavior but their call sites gain a `List.of()`
  argument. New cases: a non-empty `documentIds` produces the compound
  `projectId AND documentId IN [...]` filter (assert on the actual
  `SearchRequest`/filter expression passed to a mocked `VectorStore`, not
  just on the returned results); an empty list produces exactly today's
  `projectId`-only filter, unchanged.
- **`RagQueryServiceTest`** (existing, extended): the 3-arg `answer(...)`
  passes `documentIds` through to `RetrievalService.retrieve(...)`
  unchanged; the existing 2-arg `answer(...)` still delegates with an
  empty list — a real regression test proving the delegation, not an
  assumption.
- **`RagQueryControllerTest`** (existing, extended): a request body
  including `documentIds` passes them through to the service; a request
  omitting the field still works exactly as every existing test in this
  file already proves.
- **`RagQueryRequest`** (new small test, or extended if a test file
  already exists for this record): a `null` `documentIds` in the
  constructed record normalizes to an empty list, not a
  `NullPointerException` waiting to happen downstream.

Run `./mvnw test` after implementation.
