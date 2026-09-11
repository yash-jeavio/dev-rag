# Feature Specification: RAG Core Pipeline

## 1. Goal

Turn the existing document store into a working Retrieval-Augmented
Generation system: chunk each ingested document, embed the chunks, store them
in a vector store, and answer natural-language questions about a project's
documents with answers grounded in — and citing — that project's content.

This is **sub-project 1 of 4** in the Milestone 3 capstone. Explicitly out of
scope, each handled by its own spec:

- **Sub-project 2** — browser UI (static HTML/JS)
- **Sub-project 3** — evaluation harness (LLM-as-judge, retrieval metrics)
- **Sub-project 4** — persistence (`PgVectorStore`, persisted documents) and
  observability (per-stage latency, token usage)

## 2. Technology Decisions

| Concern | Choice | Rationale |
|---|---|---|
| Framework | Spring AI 2.0.1 (via `spring-ai-bom`) | Supports Spring Boot 4.1.x, which is a fixed constraint |
| Embeddings | Ollama `nomic-embed-text` (768 dims) | Local, free, no rate limits — ingestion is high-volume/low-value-per-call |
| Generation | Google Gemini `gemini-2.5-flash` | Strong grounded generation — queries are low-volume/high-value-per-call |
| Vector store | `SimpleVectorStore` (in-memory) | Documents and projects are in-memory; a persistent store would orphan vectors on restart |
| Chunking | Custom `TextSplitter` implementation | Spring AI's `TokenTextSplitter` has no overlap parameter |

New dependencies:

- `org.springframework.ai:spring-ai-starter-model-ollama` (version from `spring-ai-bom` 2.0.1)
- `org.springframework.ai:spring-ai-starter-model-google-genai` (version from `spring-ai-bom` 2.0.1)
- `org.apache.tika:tika-core` and `org.apache.tika:tika-parsers-standard-package`
  — replacing `org.apache.pdfbox:pdfbox`, which is removed. Pin an explicit
  version if the Spring Boot and Spring AI BOMs do not manage Tika; verify at
  implementation time rather than assuming.

Both model starters sit on the classpath simultaneously. Provider selection is
explicit, so the auto-configurations do not collide:

```properties
spring.ai.model.embedding=ollama
spring.ai.model.chat=google-genai
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.model=nomic-embed-text
spring.ai.google.genai.api-key=${GEMINI_API_KEY}
spring.ai.google.genai.chat.model=gemini-2.5-flash
```

The API key is read from the `GEMINI_API_KEY` environment variable and is never
committed.

## 3. Domain Model Changes

### `com.devassist.document` (text extraction rewrite + additive changes)

`SourceType` is extended to: `TEXT`, `MARKDOWN`, `PDF`, `WORD`, `EXCEL`,
`POWERPOINT`, `HTML`.

`DocumentTextExtractor` is rewritten on **Apache Tika** (`tika-core` +
`tika-parsers-standard-package`), replacing the direct PDFBox dependency. Tika
uses PDFBox and POI internally, so this is one dependency instead of three, and
it detects format from content rather than trusting the file extension — a
mislabelled `.txt` that is really a PDF still parses correctly.

Accepted extensions: `.txt`, `.md`, `.pdf`, `.doc`, `.docx`, `.xls`, `.xlsx`,
`.ppt`, `.pptx`, `.html`, `.htm`. Anything else is rejected with
`InvalidDocumentException` as today. `SourceType` is inferred from Tika's
detected media type, falling back to the extension.

`Document` gains one field:

- `contentHash` — String, SHA-256 hex of the extracted text, computed at
  ingestion. Used for deduplication (BR-03).

Three event records are added to this package (the publisher owns its events):

- `DocumentIngestedEvent(Document document)`
- `DocumentUpdatedEvent(Document document)`
- `DocumentDeletedEvent(String projectId, String documentId)`

`DocumentResponse` gains a `deduplicated` boolean (false for a normal ingest,
true when BR-03 returned an existing document).

`DocumentService` gains an injected `ApplicationEventPublisher` and the
deduplication lookup. Its storage model, validation, and existing method
signatures are otherwise unchanged.

### `com.devassist.rag` (new package)

| Component | Responsibility |
|---|---|
| `ChunkingService` | Implements Spring AI's `TextSplitter`; recursive character split with configurable size and overlap |
| `DocumentIndexer` | `@EventListener` for the three document events; chunks, embeds, and writes to `VectorStore` |
| `IndexStatusService` | Per-document index state (`INDEXED` or `FAILED`, chunk count, and failure reason), keyed by document id |
| `RetrievalService` | Embeds the question and runs a project-filtered similarity search |
| `ContextBuilder` | Formats retrieved chunks into a numbered context block and a citation map |
| `GenerationService` | Calls `ChatClient` with the grounded system prompt |
| `RagQueryService` | Orchestrates retrieve → build → generate |
| `RagQueryController` | `POST /api/projects/{projectId}/query` |
| `RagProperties` | `@ConfigurationProperties("devassist.rag")` |

Dependency direction is one-way: `rag` depends on `document`, never the reverse.

## 4. Chunk Metadata

Every chunk written to the vector store carries:

- `projectId` — enforces project scoping at retrieval (BR-06)
- `documentId` — allows chunk deletion on document update/delete (BR-04, BR-05)
- `title` — shown in citations
- `sourceType` — `TEXT` / `MARKDOWN` / `PDF`
- `chunkIndex` — zero-based position within the document

## 5. API

### 5.1 `POST /api/projects/{projectId}/query`

Request (`RagQueryRequest`): `question` — required, not blank, max 1000 characters.

Response (`RagAnswerResponse`), HTTP 200:

```json
{
  "question": "What is the refund window?",
  "answer": "Refunds are accepted within 30 days. [1]",
  "status": "ANSWERED",
  "sources": [
    {
      "documentId": "3f2a...",
      "title": "policy.pdf",
      "chunkIndex": 3,
      "similarity": 0.82,
      "cited": true,
      "excerpt": "Refunds are accepted within 30 days of purchase..."
    }
  ],
  "latencyMs": 1240
}
```

`status` is `ANSWERED` or `INSUFFICIENT_CONTEXT`.

### 5.2 `POST /api/projects/{projectId}/documents/{documentId}/summarize`

Summarisation deliberately does **not** reuse the query pipeline. Q&A retrieves
the `top-k` chunks most similar to a question; summarisation needs the whole
document, where similarity ranking is not merely unnecessary but wrong. The
inputs differ too — this endpoint takes a document id and no question — so a
shared endpoint with a `mode` flag would require conditional validation.

It reads `Document.content` directly rather than the vector store (BR-13), so
it works even for a document whose indexing failed (BR-11).

Request body is optional: `{ "instruction": "..." }`, max 500 characters, to
steer the summary (for example "focus on the financial terms"). Omitted means a
general summary.

Response (`SummaryResponse`), HTTP 200:

```json
{
  "documentId": "3f2a...",
  "title": "policy.pdf",
  "summary": "...",
  "charactersUsed": 48231,
  "truncated": false,
  "latencyMs": 2100
}
```

### 5.3 `GET /api/projects/{projectId}/documents/{documentId}/index-status`

Response, HTTP 200: `{ "documentId": "...", "status": "INDEXED", "chunkCount": 12, "reason": null }`.

`status` is `INDEXED` or `FAILED`; `reason` is populated only when `FAILED`.

### 5.4 Existing document endpoints

Behaviour is unchanged except for deduplication (BR-03): a duplicate ingest
returns **200** with the existing document rather than **201**, and the
response body gains a `deduplicated` boolean.

### 5.5 New list endpoints

Both are plain CRUD rather than RAG, but the browser UI in sub-project 2 cannot
function without them — it has to show what already exists, and today a caller
can only fetch a project or document whose UUID they already know.

- `GET /api/projects` → HTTP 200, `List<ProjectResponse>`, all projects.
- `GET /api/projects/{projectId}/documents` → HTTP 200,
  `List<DocumentResponse>`, only that project's documents (BR-12). Returns 404
  for an unknown project, and an empty list for a project with no documents.

Neither response includes index status; the UI fetches that per document from
5.3. That keeps the `document` package free of any dependency on `rag`, at the
cost of N+1 calls — acceptable for the corpus sizes this project targets, and
revisitable in sub-project 4 if it becomes a problem.

### 5.6 Default project (UI behaviour, sub-project 2)

The Project layer is retained as the corpus boundary, but is not surfaced as a
required step in the user flow. The UI creates or reuses a single default
project on first upload, so the experience is upload → ask. A project selector
may be added later for users who want separate knowledge bases. No backend
change is required for this: it is entirely a sub-project 2 concern, recorded
here so the intent is not lost.

### 5.7 DTO inventory and forward compatibility

All DTOs are immutable Java records. They are listed here so the shapes are
decided once rather than evolved ad hoc, since later sub-projects and the
browser UI both consume them.

**Requests:** `RagQueryRequest(String question)`,
`SummarizeRequest(String instruction)`.

**Responses:**

| Record | Fields |
|---|---|
| `RagAnswerResponse` | `question`, `answer`, `status`, `sources`, `latencyMs`, `evaluation` |
| `SourceReference` | `documentId`, `title`, `chunkIndex`, `similarity`, `cited`, `excerpt` |
| `SummaryResponse` | `documentId`, `title`, `summary`, `charactersUsed`, `truncated`, `latencyMs` |
| `IndexStatusResponse` | `documentId`, `status`, `chunkCount`, `reason` |

Three deliberate choices exist to avoid reworking these later:

- **`status` is an enum, not a boolean.** `ANSWERED` / `INSUFFICIENT_CONTEXT`
  today; new outcomes can be added without changing the field's type or
  breaking existing consumers.
- **`RagAnswerResponse.evaluation` is reserved now and always `null` in this
  sub-project.** Sub-project 3 populates it with judge scores. Declaring it up
  front means the API contract and the UI that reads it do not change when
  evaluation lands.
- **`latencyMs` is a single total.** Sub-project 4 adds a per-stage breakdown as
  a *new* nested field rather than altering this one, so existing callers keep
  working.

`SourceReference` is shared rather than duplicated per endpoint: the evaluation
harness needs the same shape to report which chunks a judge saw.

## 6. Business Rules

- **BR-01**: Indexing is *attempted* synchronously for every newly ingested
  document, before the ingest request returns — events use Spring's default
  synchronous publisher and must not be `@Async`. Whether that attempt succeeds
  is governed by BR-11; this rule fixes the timing, not the outcome.
- **BR-02**: Chunking splits on paragraph, then sentence, then word
  boundaries, targeting `chunk-size-chars` with `chunk-overlap-chars` of
  overlap between consecutive chunks. A single word longer than the chunk size
  is emitted as its own chunk rather than dropped or split mid-word.
- **BR-03**: Ingesting content whose SHA-256 matches an existing document **in
  the same project** returns that existing document unchanged, publishes no
  event, and performs no embedding. The same content in a *different* project
  is a separate document, because projects are separate corpora.
- **BR-04**: Updating a document deletes all of its existing chunks from the
  vector store and indexes the new content. Chunks from the previous version
  must never remain retrievable.
- **BR-05**: Deleting a document deletes all of its chunks from the vector
  store.
- **BR-06**: Retrieval is always filtered to the requested `projectId`. A query
  must never return chunks belonging to another project.
- **BR-07**: Retrieval returns at most `top-k` chunks, and only those whose
  similarity score is greater than or equal to `similarity-threshold`.
- **BR-08**: When no chunk clears the threshold, the response is
  `INSUFFICIENT_CONTEXT` and **the chat model is not called at all**.
- **BR-09**: The system prompt constrains the model to answer only from the
  supplied context, to reply with a fixed exact phrase when the context is
  insufficient, and to cite sources with `[n]` markers.
- **BR-10**: All retrieved chunks are returned in `sources`, each with its
  similarity score. A chunk whose `[n]` marker appears in the answer is flagged
  `cited: true`. Absent, malformed, or out-of-range markers leave every chunk
  `cited: false` and must not raise an error.
- **BR-11**: Indexing failures do not fail the ingest request. The document is
  stored, `IndexStatusService` records `FAILED` with the reason, and the
  document is re-indexable later.
- **BR-12**: `GET /api/projects/{projectId}/documents` returns only documents
  whose `projectId` matches the path. This is the same isolation guarantee as
  BR-06, applied to listing rather than retrieval — a listing leak would expose
  other projects' document titles.
- **BR-13**: Summarisation reads `Document.content` — the full extracted text —
  directly, and does **not** touch the vector store. Chunks are deliberately
  overlapping, so reassembling them would feed the model every overlap region
  twice; and `SimpleVectorStore` offers no pure metadata query, so a
  chunk-based approach would also require a throwaway embedding call purely to
  satisfy the search API. Neither cost buys anything the stored content does
  not already provide.
- **BR-14**: If the document's text exceeds `summary-max-chars`, the leading
  `summary-max-chars` characters are summarised, the remainder is dropped, and
  the response reports `truncated: true` alongside `charactersUsed`. Silent
  truncation is not acceptable — the caller must be able to tell that the
  summary covers only part of the document.
- **BR-15**: File type is determined by Tika's detected media type, with the
  file extension as a fallback. A file whose detected type is outside the
  accepted set is rejected with `InvalidDocumentException`, even if its
  extension is acceptable.

## 7. Error Conditions

| Condition | Status | Handling |
|---|---|---|
| Unknown project | 404 | Existing `ProjectNotFoundException` |
| Unknown document (index-status) | 404 | Existing `DocumentNotFoundException` |
| Blank or missing `question` | 400 | `@Valid` + `@NotBlank`, same error shape as existing controllers |
| `question` over 1000 characters | 400 | `@Size` |
| Embedding provider unreachable during **indexing** | 201/200 | Ingest succeeds; status recorded `FAILED` (BR-11) |
| Embedding provider unreachable during **query** | 503 | The question cannot be embedded, so there is nothing honest to return |
| Chat model unreachable or quota exceeded | 503 | Returned after retrieval succeeded, so the failure is clearly attributable to generation |
| No chunk clears the threshold | 200 | `INSUFFICIENT_CONTEXT` (BR-08) |

A `RagExceptionHandler` (`@RestControllerAdvice(assignableTypes = RagQueryController.class)`)
handles the RAG-specific cases, mirroring the existing per-controller advice
pattern.

## 8. Configuration

`@ConfigurationProperties("devassist.rag")`:

| Property | Default | Meaning |
|---|---|---|
| `top-k` | 5 | Maximum chunks retrieved per query |
| `similarity-threshold` | 0.5 | Minimum cosine similarity to include a chunk |
| `chunk-size-chars` | 2000 | Target chunk size (~500 tokens) |
| `chunk-overlap-chars` | 200 | Overlap between consecutive chunks |
| `temperature` | 0.1 | Chat model temperature — low for factual grounding |
| `max-excerpt-chars` | 300 | Excerpt length returned per source |
| `summary-max-chars` | 200000 | Character budget for summarisation input (BR-14) |

The 0.5 threshold is empirical, not arbitrary: measured against
`nomic-embed-text`, a relevant chunk scored 0.78 and an unrelated one 0.37.
Thresholds are model-specific and are re-tuned in sub-project 3.

## 9. Non-Functional Requirements

- Two new dependencies only, both version-managed by `spring-ai-bom`.
- Constructor injection throughout; thin controllers; immutable records for
  request/response DTOs.
- `SimpleVectorStore` is wired as a `VectorStore` bean. No pipeline code may
  depend on the concrete implementation, so sub-project 4 can substitute
  `PgVectorStore` by changing only the bean definition.
- No secrets in source or in `application.properties` beyond an environment
  variable reference.
- The `document` package's existing behaviour and its 56 passing tests remain
  green.

## 10. Testing

Tests must pass with no Ollama, no Gemini key, and no network access.

- **`ChunkingServiceTest`** (plain JUnit): overlap correctness between
  consecutive chunks; paragraph/sentence/word boundary preference; text shorter
  than one chunk; empty text; a single word longer than the chunk size;
  configured size and overlap are respected (BR-02).
- **`DocumentIndexerTest`** (mocked `VectorStore`, `EmbeddingModel`): chunks are
  written with correct metadata; update deletes prior chunks before adding new
  ones (BR-04); delete removes chunks (BR-05); a throwing embedder records
  `FAILED` and does not propagate (BR-11).
- **`RetrievalServiceTest`** (mocked `VectorStore`): the `projectId` filter is
  always applied (BR-06); `top-k` and threshold are honoured (BR-07).
- **`RagQueryServiceTest`** (mocked retrieval and `ChatClient`): the chat model
  is never invoked when no chunk clears the threshold (BR-08); citation parsing
  flags the right sources and tolerates absent, malformed, and out-of-range
  markers (BR-10).
- **`DocumentServiceTest`** (extends the existing class): identical content
  ingested twice returns the same document id, publishes one event, and embeds
  once (BR-03); identical content in two projects yields two documents.
- **`RagQueryControllerTest`** (`@WebMvcTest` + `@MockitoBean`): 200 with
  sources; 400 for a blank question; 404 for an unknown project — mirroring the
  existing `DocumentControllerTest` pattern.
- **List endpoint tests** (extending the existing controller and service test
  classes): all projects are returned; a project's document list contains only
  its own documents and never another project's (BR-12); an unknown project
  returns 404; a project with no documents returns an empty list rather than
  404.
- **`SummarizationServiceTest`** (mocked `ChatClient`): the document's full
  stored content is summarised and the vector store is never consulted (BR-13);
  text over `summary-max-chars` sets `truncated: true` and reports the real
  `charactersUsed` (BR-14); text under the budget reports `truncated: false`;
  an optional `instruction` is passed through to the prompt.
- **`DocumentTextExtractorTest`** (extends the existing class): each newly
  accepted extension extracts text — `.docx`, `.xlsx`, `.pptx`, `.html` — using
  small fixture files committed under `src/test/resources`; a file whose
  detected type contradicts an acceptable extension is rejected (BR-15); a
  corrupt file of an accepted type is rejected rather than stored empty.

Run `./mvnw test` after implementation.
