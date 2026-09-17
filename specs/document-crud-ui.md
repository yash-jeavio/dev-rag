# Feature Specification: Document View, Rename, and Delete in the Web UI

## 1. Goal

Let a user view, rename, and delete a document directly from the manual
test UI's document list, via a per-row 3-dot menu — closing the gap where
`DocumentController` already has full CRUD (`GET`, `PUT`, `DELETE`) but the
UI only ever exercises `POST`/`GET`, leaving update and delete reachable
only through the `document-api.http` REST-client file.

Out of scope: editing a document's *content* (rename changes only the
title); any change to how documents are chunked, embedded, or indexed
beyond what a rename must trigger (§3); a rich, format-preserving preview
of the original file (only extracted plain text is ever stored — see §4).

## 2. Why Two of the Three Operations Are Not Just UI Wiring

Read directly from the current source before writing this spec:

- **View** needs no backend change. `GET /api/projects/{projectId}/documents/{documentId}`
  already returns a `DocumentResponse` carrying `title`, `sourceType`, and
  the full extracted `content`. Pure UI work.
- **Delete** needs no backend change either. `DELETE
  /api/projects/{projectId}/documents/{documentId}` already removes the
  document from `DocumentService`'s store and publishes
  `DocumentDeletedEvent`, which `DocumentIndexer.onDeleted` already
  consumes to delete the document's chunks from the vector store
  (`DocumentIndexer.java:64-82`). Pure UI work.
- **Rename** cannot be built on the two existing update endpoints without
  either corrupting data or wasting work:
  - `PUT .../documents/{documentId}` (file) and `PUT
    .../documents/{documentId}/text` (`IngestTextRequest`, `DocumentService.
    updateText`, `DocumentService.java:92-100`) both unconditionally set
    `sourceType` to whatever the request implies (`SourceType.TEXT` for the
    text endpoint) and recompute `contentHash` from newly-supplied content.
    Using either to "just change the title" of a document originally
    uploaded as, say, a PDF would silently flip its `sourceType` to `TEXT`
    — a real data-corruption risk, not a inconvenience.
  - Both existing update paths also publish `DocumentUpdatedEvent`, which
    `DocumentIndexer.onUpdated` handles by deleting and fully re-embedding
    every chunk (`DocumentIndexer.java:44-62`) — a legitimate cost when
    content actually changed, but pure waste for a title-only edit.

  This spec therefore adds one new, narrow backend capability: a rename
  operation that changes only `title`, leaving `content`, `sourceType`,
  and `contentHash` untouched.

## 3. Why Rename Still Needs to Re-Index

Every chunk written to the vector store carries the document's `title` in
its metadata (`DocumentIndexer.java:93-98`), because citations read it back
from there (confirmed against the RAG core's existing citation path). If
rename updated `DocumentService`'s own record but never touched the vector
store, every chunk already indexed would keep showing the *old* title in
citations indefinitely — a real, user-visible inconsistency between the
document list (new title) and any answer's sources (old title).

The cheapest correct fix is to reuse the exact mechanism `updateText`/
`updateFile` already use: `DocumentService.rename(...)` builds the updated
`Document` (title changed, everything else identical) and publishes the
existing `DocumentUpdatedEvent`. `DocumentIndexer.onUpdated` is completely
unchanged by this spec — it already deletes and re-indexes on that event,
and will do so correctly with the new title in the refreshed chunks'
metadata. No new indexing logic is written; the existing, already-tested
listener is simply given a reason to fire for a title-only change too.

## 4. Why "View" Can Only Show Extracted Text

`DocumentTextExtractor` (used by `ingestFile`) discards the original file
bytes after extracting `content` — `Document` has no field for them
(`Document.java:5-14`). This means "view" can never render a pixel-perfect
original (a real PDF layout, embedded images, Word styling); it can only
display what's actually stored: the extracted plain text. Per the approved
design, `SourceType.MARKDOWN` documents render with real Markdown
formatting (headers, emphasis, lists, code, links); every other source
type (`TEXT`, `PDF`, `WORD`, `EXCEL`, `POWERPOINT`, `HTML`) displays its
extracted text as plain preformatted text, since none of those are stored
as anything richer than a plain string.

## 5. Changed Components

| File | Change |
|---|---|
| `com.devassist.document.RenameDocumentRequest` | New record: `@NotBlank @Size(max = 200) String title` — same constraint as `IngestTextRequest.title()`. |
| `com.devassist.document.DocumentService` | New method `rename(String projectId, String documentId, String newTitle)`: builds an updated `Document` with only `title` changed, stores it, publishes `DocumentUpdatedEvent`, returns the updated `Document`. |
| `com.devassist.document.DocumentController` | New `@PutMapping(path = "/{documentId}/title", consumes = APPLICATION_JSON_VALUE)` method, mirroring the existing `/{documentId}/text` endpoint's shape: `@Valid @RequestBody RenameDocumentRequest`, returns `ResponseEntity<DocumentResponse>` via `DocumentResponse.from(...)`. |
| `src/main/resources/static/index.html` | New per-document 3-dot menu (View / Rename / Delete), a full-screen view overlay (with a small dependency-free Markdown renderer for `SourceType.MARKDOWN`), a rename dialog, and a delete confirmation dialog. No new dependency — CLAUDE.md forbids adding one to this plain HTML/CSS/JS UI. |

No new exception type, no new `DocumentExceptionHandler` entry — `rename`
reuses the exact not-found/validation paths `updateText`/`updateFile`
already exercise.

## 6. API

```
PUT /api/projects/{projectId}/documents/{documentId}/title
Content-Type: application/json

{ "title": "Refund Policy v2" }
```

Returns `200 OK` with the updated `DocumentResponse` (same shape `GET`
already returns) on success; `400` with the existing validation-error
shape if `title` is blank or over 200 characters; `404` if the project or
document doesn't exist — identical error handling to every other
`DocumentController` endpoint, via the existing `DocumentExceptionHandler`.

## 7. Business Rules

- **BR-01**: Renaming a document changes only its `title`. `content`,
  `sourceType`, `contentHash`, and `createdAt` are byte-identical before
  and after.
- **BR-02**: A rename publishes `DocumentUpdatedEvent`, causing
  `DocumentIndexer` to delete and re-index the document's chunks with the
  new title in their metadata — the same behavior a content update
  already triggers, unchanged by this spec. This guarantees citations
  never show a stale title after a rename completes.
- **BR-03**: The View screen renders a `MARKDOWN`-sourced document's
  content with Markdown formatting (headers, bold/italic, lists, inline
  code, fenced code blocks, links) via a small hand-written renderer with
  no external dependency. Every other `SourceType` renders its extracted
  content as plain preformatted text.
- **BR-04**: Deleting a document always asks for confirmation first
  ("Are you sure you want to delete this document?"). Declining
  (clicking "No") makes zero network calls and leaves the UI and document
  list exactly as they were. Confirming ("Yes") calls the existing
  `DELETE` endpoint and, only on a successful response, removes the row
  from the displayed list.
- **BR-05**: A rename or delete that fails (network error, 404, 400)
  shows the user a clear error message and leaves the document list
  showing its state from before the attempted operation — no row is
  removed or renamed in the UI ahead of a confirmed successful response
  from the server.

## 8. Error Conditions

| Condition | Status | Handling |
|---|---|---|
| `title` blank or > 200 chars on rename | 400 | Existing `MethodArgumentNotValidException` handling in `DocumentExceptionHandler` — dialog shows the field error inline, stays open for correction. |
| Project or document not found (rename or delete) | 404 | Existing `ProjectNotFoundException`/`DocumentNotFoundException` handling — UI shows an error message; per BR-05, no optimistic UI change is made. |
| Any other failure (network, 500) | varies | UI shows a generic error message; per BR-05, no optimistic UI change is made. |

## 9. Non-Functional Requirements

- No new dependency, frontend or backend — the rename endpoint reuses
  Spring MVC/validation already on the classpath; the Markdown renderer is
  hand-written, no CDN script.
- No change to `DocumentIndexer`, `ChunkingService`, or any retrieval/
  generation code — this spec is additive at the `DocumentService`/
  `DocumentController`/UI layer only.
- Constructor injection and immutable records maintained throughout.

## 10. Testing

- **`DocumentServiceTest`** (existing, extended): `rename(...)` changes
  only `title` (assert `content`/`sourceType`/`contentHash`/`createdAt`
  are unchanged via `equals`/field assertions on the returned `Document`);
  publishes `DocumentUpdatedEvent` with the renamed document (verify via a
  captured/mocked `ApplicationEventPublisher`, matching how existing
  `updateText`/`updateFile` tests already verify event publication);
  throws `DocumentNotFoundException`/`ProjectNotFoundException` for an
  unknown document/project, matching every other method's existing
  behavior.
- **`DocumentControllerTest`** (existing, extended): a valid rename
  request returns `200` with the updated title in the body; a blank title
  returns `400` with the existing field-error shape; renaming a
  nonexistent document returns `404`.
- **UI**: no automated test, per this project's established convention for
  `index.html` (already the case for the chat-provider dropdown and the
  document-scoped-retrieval checkboxes). Manual verification: upload a
  PDF and a Markdown file; open View on each and confirm the Markdown one
  renders formatting while the PDF one shows plain text; rename the PDF
  one and confirm the list updates and a subsequent question against it
  still returns a citation with the new title; delete a document, decline
  the confirmation (nothing happens), then delete and confirm (row
  disappears, and a subsequent query no longer surfaces it as a source).

Run `./mvnw test` after implementation.
