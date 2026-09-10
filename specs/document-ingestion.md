# Feature Specification: Document Ingestion Pipeline

## 1. Goal

Allow a client to ingest documents (by file upload or raw text) into a
`Project`, extract their plain-text content, and manage them (create, read,
update, delete) so that later Milestone 3 stages (chunking, embeddings,
vector storage, retrieval) have a stable source of document content to
consume.

This spec covers **ingestion only**. Chunking, embeddings, vector storage,
retrieval, Docker, and Postgres/PGVector are explicitly out of scope and are
handled by later sub-projects per the Milestone 3 breakdown.

## 2. Domain Model

New package: `com.devassist.document`.

`SourceType` (enum): `TEXT`, `MARKDOWN`, `PDF`.

`Document` (record):

- `id` — String, generated (`UUID.randomUUID()`), same pattern as `Project`.
- `projectId` — String, the owning project's id.
- `title` — String.
- `sourceType` — `SourceType`.
- `content` — String, extracted plain text.
- `createdAt` — `Instant`, set once at ingestion and never changed by `PUT`.

`DocumentResponse` (record): `id`, `projectId`, `title`, `sourceType`,
`content`, `createdAt`, with a `from(Document)` static factory, mirroring
`ProjectResponse`.

## 3. New Components

- `Document`, `SourceType`, `DocumentResponse`
- `IngestTextRequest` (record: `title`, `content`) — used by both the text
  create and text update endpoints.
- `DocumentController` — thin, constructor-injected `DocumentService`.
- `DocumentService` — business logic; constructor-injected `ProjectService`
  to validate the owning project exists; stores documents in a
  `ConcurrentHashMap<String, Document>`.
- `DocumentTextExtractor` — single component (not a strategy hierarchy)
  with a method to extract plain text from a filename + byte array: reads
  `.txt`/`.md` as UTF-8, uses Apache PDFBox `PDFTextStripper` for `.pdf`.
- `DocumentNotFoundException` (404).
- `InvalidDocumentException` (400) — unsupported extension, empty file,
  content/file exceeding the size limit, or text extraction failure.
- `DocumentExceptionHandler` — `@RestControllerAdvice(assignableTypes =
  DocumentController.class)`, mirroring `ProjectExceptionHandler`.

## 4. API Endpoints

All endpoints are nested under an existing project. Every operation first
calls `ProjectService.findById(projectId)`; if the project doesn't exist,
the existing `ProjectNotFoundException` propagates and reuses the 404
behavior already established by `ProjectExceptionHandler`'s pattern (handled
in `DocumentExceptionHandler` since it's scoped to `DocumentController`).

### 4.1 `POST /api/projects/{projectId}/documents`

- Content-Type: `multipart/form-data`, file part named `file`.
- Extension must be `.txt`, `.md`, or `.pdf` (case-insensitive); source
  type is inferred from it.
- Title is the original filename exactly as received, including its
  extension (e.g. `notes.txt`).
- Response: HTTP 201 Created, `Location: /api/projects/{projectId}/documents/{id}`,
  body `DocumentResponse`.

### 4.2 `POST /api/projects/{projectId}/documents/text`

- Content-Type: `application/json`, body `IngestTextRequest`, validated
  with `@Valid`.
- Always stored as `SourceType.TEXT`.
- Response: HTTP 201 Created, same `Location`/body shape as 4.1.

### 4.3 `GET /api/projects/{projectId}/documents/{documentId}`

- Response: HTTP 200 OK, body `DocumentResponse`.

### 4.4 `PUT /api/projects/{projectId}/documents/{documentId}`

- Content-Type: `multipart/form-data`, file part named `file`; same
  validation as 4.1.
- Full replacement of `title`, `sourceType`, and `content`. `id`,
  `projectId`, and `createdAt` are preserved.
- Response: HTTP 200 OK, body `DocumentResponse`.

### 4.5 `PUT /api/projects/{projectId}/documents/{documentId}/text`

- Content-Type: `application/json`, body `IngestTextRequest`, validated
  with `@Valid`.
- Full replacement of `title` and `content`; `sourceType` remains `TEXT`.
- Response: HTTP 200 OK, body `DocumentResponse`.

### 4.6 `DELETE /api/projects/{projectId}/documents/{documentId}`

- Response: HTTP 204 No Content.

## 5. Inputs

`IngestTextRequest` fields:

- `title` — required, not blank, maximum 200 characters.
- `content` — required, not blank.

File upload (4.1, 4.4):

- `file` — required, non-empty, extension one of `.txt`/`.md`/`.pdf`.

Path parameters:

- `projectId` — String, required, identifying an existing project.
- `documentId` — String, required, identifying an existing document
  (4.3–4.6).

## 6. Business Rules

- A document belongs to exactly one project; `projectId` is immutable
  once created.
- `GET`, `PUT`, and `DELETE` must treat a document as not found (404) if
  its `id` exists but its `projectId` does not match the path's
  `projectId` — this prevents reading/modifying/deleting another
  project's document by guessing an id.
- **Size limit: both ingestion paths are capped at 10 MB (10 * 1024 * 1024
  = 10,485,760 bytes)**, matching how Spring's `DataSize` interprets the
  `10MB` unit used in the multipart config. For file uploads this is
  enforced by Spring's multipart configuration; for the raw-text JSON
  path, `DocumentService` explicitly checks the UTF-8 byte length of
  `content` against the same constant and rejects anything over it with
  `InvalidDocumentException`. This applies to both create and update.
- `.txt`/`.md` content is read as UTF-8 text; `.pdf` content is extracted
  via PDFBox's `PDFTextStripper`. A file that fails extraction (e.g. a
  corrupt PDF) is rejected, not stored.
- `PUT` performs full replacement of the mutable fields (`title`,
  `content`, and — for the file path — `sourceType`); it does not create a
  new document for an unknown id.
- Storage is the existing in-memory `ConcurrentHashMap` pattern; no
  persistence, database, or vector store is introduced by this feature.

## 7. Error Conditions

### Project not found

- HTTP 404 Not Found.
- Reuse the existing `ProjectNotFoundException` and its message format.

### Document not found (or belongs to a different project)

- HTTP 404 Not Found.
- New `DocumentNotFoundException`, handled by `DocumentExceptionHandler`.

### Invalid document (unsupported extension, empty file, extraction
failure, or size limit exceeded on the text path)

- HTTP 400 Bad Request.
- New `InvalidDocumentException`, handled by `DocumentExceptionHandler`.

### Multipart file exceeds the configured size limit

- HTTP 400 Bad Request.
- `DocumentExceptionHandler` also handles Spring's
  `MaxUploadSizeExceededException`.

### Validation failure (text JSON body)

- HTTP 400 Bad Request.
- Same shape as the existing `MethodArgumentNotValidException` handling in
  `ProjectExceptionHandler` (`{status, errors: [{field, message}]}`),
  duplicated in `DocumentExceptionHandler` since advice is scoped
  per-controller.

### Malformed JSON / unsupported media type

- HTTP 400 / 415 respectively.
- Existing/default Spring MVC handling is acceptable; no new handling
  required.

## 8. Non-Functional Requirements

- New dependency: `org.apache.pdfbox:pdfbox`, version `3.0.8` pinned
  explicitly (not covered by the Spring Boot BOM). No other new
  dependencies.
- `application.properties`: set `spring.servlet.multipart.max-file-size=10MB`
  and `spring.servlet.multipart.max-request-size=10MB`.
- Continue using constructor injection, thin controllers, and immutable
  records for request/response DTOs.
- Keep the existing thread-safe `ConcurrentHashMap` pattern; no database or
  persistence layer.
- Do not modify the `project` package beyond calling its existing public
  `ProjectService.findById`.

## 9. Testing

- `DocumentServiceTest` (plain JUnit, no Spring context): text ingestion,
  markdown file ingestion, PDF file ingestion, unsupported extension
  rejection, empty file rejection, oversized content rejection (both
  paths), unknown project rejection, get/update/delete on unknown
  document, cross-project get/update/delete rejection, full-replace
  semantics on update.
- `DocumentControllerTest` (`@WebMvcTest(DocumentController.class)`,
  `DocumentService` mocked via `@MockitoBean`): successful create (both
  paths), successful get/update/delete, validation errors on the text
  path, 404s for missing project/document, 400 for oversized multipart
  upload.
- Run `./mvnw test` after implementation.
