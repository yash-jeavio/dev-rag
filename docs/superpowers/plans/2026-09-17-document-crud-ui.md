# Document View, Rename, and Delete in the Web UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a per-document 3-dot menu (View / Rename / Delete) to the manual test UI, backed by a new lightweight rename capability and the already-existing GET/DELETE endpoints.

**Architecture:** One small backend addition (a title-only rename endpoint that reuses the existing `DocumentUpdatedEvent`/`DocumentIndexer` re-indexing path, so citations never show a stale title) plus a UI-only task adding a dropdown menu, a full-screen view overlay with a hand-written Markdown renderer, a rename dialog, and a delete confirmation dialog to `index.html`.

**Tech Stack:** Java 17, Spring Boot 4.1.1, plain HTML/CSS/JS (no frontend framework or dependency).

**Spec:** `specs/document-crud-ui.md`

## Global Constraints

- No new exception type, no new `DocumentExceptionHandler` entry — rename reuses the exact validation/not-found paths `updateText`/`updateFile` already exercise.
- No new backend or frontend dependency — the Markdown renderer is hand-written, no CDN script.
- `DocumentIndexer`, `ChunkingService`, and all retrieval/generation code are not touched — rename's correctness depends entirely on reusing the existing `DocumentUpdatedEvent` publish, not on any new indexing logic.
- Rename changes only `title` — `content`, `sourceType`, `contentHash`, and `createdAt` stay byte-identical.
- Run `./mvnw test` after Task 1; the baseline going into this plan is **247 tests, 0 failures, 0 errors**.

---

### Task 1: Backend rename capability

**Files:**
- Create: `src/main/java/com/devassist/document/RenameDocumentRequest.java`
- Modify: `src/main/java/com/devassist/document/DocumentService.java`
- Modify: `src/main/java/com/devassist/document/DocumentController.java`
- Test: `src/test/java/com/devassist/document/DocumentServiceTest.java`
- Test: `src/test/java/com/devassist/document/DocumentControllerTest.java`

**Interfaces:**
- Consumes: nothing from another task.
- Produces: `DocumentService.rename(String projectId, String documentId, String newTitle) -> Document` and `PUT /api/projects/{projectId}/documents/{documentId}/title` (body `{"title": "..."}`, returns `DocumentResponse`) — Task 2 (the UI) calls this endpoint directly; it does not call the service method.

- [ ] **Step 1: Write the failing tests for `DocumentService.rename(...)`**

In `src/test/java/com/devassist/document/DocumentServiceTest.java`, add these four tests (anywhere among the existing `@Test` methods — e.g. right after `updateTextThrowsWhenDocumentBelongsToDifferentProject`, at line 204):

```java
	@Test
	void renameChangesOnlyTitle() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Original", "Body"))
				.document();

		Document renamed = documentService.rename(existingProjectId, created.id(), "New Title");

		assertThat(renamed.id()).isEqualTo(created.id());
		assertThat(renamed.projectId()).isEqualTo(created.projectId());
		assertThat(renamed.title()).isEqualTo("New Title");
		assertThat(renamed.sourceType()).isEqualTo(created.sourceType());
		assertThat(renamed.content()).isEqualTo(created.content());
		assertThat(renamed.contentHash()).isEqualTo(created.contentHash());
		assertThat(renamed.createdAt()).isEqualTo(created.createdAt());
	}

	@Test
	void renameThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.rename(existingProjectId, "unknown-id", "New Title"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void renameThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Original", "Body"))
				.document();
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.rename(otherProject.id(), created.id(), "New Title"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void publishesUpdatedEventOnRename() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Original", "Body"))
				.document();
		clearInvocations(eventPublisher);

		Document renamed = documentService.rename(existingProjectId, created.id(), "New Title");

		verify(eventPublisher).publishEvent(new DocumentUpdatedEvent(renamed));
	}
```

No new imports are needed — `Document`, `DocumentNotFoundException`, `IngestTextRequest`, `Project`, `CreateProjectRequest`, `DocumentUpdatedEvent`, `assertThat`, `assertThatThrownBy`, `clearInvocations`, and `verify` are all already imported in this file.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentServiceTest`
Expected: FAIL — compile error, `DocumentService` has no `rename(...)` method yet.

- [ ] **Step 3: Add `DocumentService.rename(...)`**

In `src/main/java/com/devassist/document/DocumentService.java`, add this method immediately after `updateText(...)` (which currently ends at line 100, right before `findByProject(...)`):

```java
	public Document rename(String projectId, String documentId, String newTitle) {
		Document existing = findById(projectId, documentId);
		Document updated = new Document(existing.id(), existing.projectId(), newTitle, existing.sourceType(),
				existing.content(), existing.contentHash(), existing.createdAt());
		documents.put(updated.id(), updated);
		eventPublisher.publishEvent(new DocumentUpdatedEvent(updated));
		return updated;
	}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentServiceTest`
Expected: PASS (all tests in the file, including the 4 new ones).

- [ ] **Step 5: Write the failing tests for the controller endpoint**

In `src/test/java/com/devassist/document/DocumentControllerTest.java`, add these three tests (anywhere among the existing `@Test` methods — e.g. right after `updateFromTextReturnsBadRequestWhenTitleIsMissing`, at line 220):

```java
	@Test
	void renameReturnsOkWithUpdatedTitle() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document renamed = new Document("doc-1", "project-1", "New Title", SourceType.TEXT, "Body text", "hash-1",
				createdAt);
		given(documentService.rename(eq("project-1"), eq("doc-1"), eq("New Title"))).willReturn(renamed);

		mockMvc.perform(put("/api/projects/{projectId}/documents/{documentId}/title", "project-1", "doc-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"title": "New Title"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("New Title"));
	}

	@Test
	void renameReturnsBadRequestWhenTitleIsMissing() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/documents/{documentId}/title", "project-1", "doc-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"));
	}

	@Test
	void renameReturnsNotFoundWhenDocumentDoesNotExist() throws Exception {
		given(documentService.rename(eq("project-1"), eq("missing"), any()))
				.willThrow(new DocumentNotFoundException("missing"));

		mockMvc.perform(put("/api/projects/{projectId}/documents/{documentId}/title", "project-1", "missing")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"title": "New Title"
						}
						"""))
				.andExpect(status().isNotFound());
	}
```

No new imports are needed — `Instant`, `Document`, `SourceType`, `DocumentNotFoundException`, `MediaType`, `given`, `eq`, `any`, `put`, `status`, and `jsonPath` are all already imported in this file.

- [ ] **Step 6: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: FAIL — compile error, `DocumentController` has no rename endpoint and `RenameDocumentRequest` doesn't exist yet.

- [ ] **Step 7: Create `RenameDocumentRequest`**

Create `src/main/java/com/devassist/document/RenameDocumentRequest.java`:

```java
package com.devassist.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameDocumentRequest(@NotBlank @Size(max = 200) String title) {
}
```

- [ ] **Step 8: Add the rename endpoint to `DocumentController`**

In `src/main/java/com/devassist/document/DocumentController.java`, add this method immediately after `updateFromText(...)` (which currently ends at line 82, right before `delete(...)`):

```java
	@PutMapping(path = "/{documentId}/title", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentResponse> rename(@PathVariable String projectId, @PathVariable String documentId,
			@Valid @RequestBody RenameDocumentRequest request) {
		Document document = documentService.rename(projectId, documentId, request.title());
		return ResponseEntity.ok(DocumentResponse.from(document));
	}
```

No new imports are needed in `DocumentController.java` — `MediaType`, `Valid`, `RequestBody`, `PutMapping`, `PathVariable`, `ResponseEntity` are all already imported.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: PASS (all tests in the file, including the 3 new ones).

Run: `./mvnw test`
Expected: PASS, 254 tests (247 baseline + 4 `DocumentServiceTest` + 3 `DocumentControllerTest`), 0 failures, 0 errors.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/devassist/document/RenameDocumentRequest.java \
  src/main/java/com/devassist/document/DocumentService.java \
  src/main/java/com/devassist/document/DocumentController.java \
  src/test/java/com/devassist/document/DocumentServiceTest.java \
  src/test/java/com/devassist/document/DocumentControllerTest.java
git commit -m "feat: add a title-only rename endpoint for documents"
```

---

### Task 2: 3-dot menu (View / Rename / Delete) in the web UI

**Files:**
- Modify: `src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `GET /api/projects/{projectId}/documents/{documentId}` (existing, returns `title`/`sourceType`/`content`), `PUT /api/projects/{projectId}/documents/{documentId}/title` (Task 1, body `{"title": "..."}`), `DELETE /api/projects/{projectId}/documents/{documentId}` (existing).
- Produces: nothing — this is the last task.

No automated test — `index.html` has no test harness in this project (the same pattern as the chat-provider-switching and document-scoped-retrieval plans' own UI tasks). Verified manually in Step 5.

The current file is 423 lines. This task touches four distinct regions of it: the `<style>` block, the markup right after `</main>`, the `refreshDocs()` function, and a new block of JavaScript functions. Each step below names the exact current text to find and what to replace it with.

- [ ] **Step 1: Add the new CSS**

In `src/main/resources/static/index.html`, find this exact block (the last two rules before `</style>`, currently lines 107-109):

```css
	.spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.7s linear infinite; vertical-align: middle; margin-right: 8px; }
	@keyframes spin { to { transform: rotate(360deg); } }
</style>
```

Replace it with:

```css
	.spinner { display: inline-block; width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--accent); border-radius: 50%; animation: spin 0.7s linear infinite; vertical-align: middle; margin-right: 8px; }
	@keyframes spin { to { transform: rotate(360deg); } }
	.menu-btn { padding: 5px 10px; font-size: 14px; margin-left: 6px; line-height: 1; }
	.dropdown-menu {
		position: fixed; z-index: 20; background: var(--panel); border: 1px solid var(--border);
		border-radius: 6px; box-shadow: 0 4px 16px rgba(0,0,0,0.4); min-width: 160px; overflow: hidden;
	}
	.dropdown-menu button {
		display: block; width: 100%; text-align: left; background: transparent; border: none; color: var(--text);
		padding: 10px 14px; font-size: 13px; border-radius: 0; cursor: pointer;
	}
	.dropdown-menu button:hover { background: rgba(255,255,255,0.06); }
	.dropdown-menu button.danger { color: var(--bad); }
	.modal-overlay {
		position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex;
		align-items: center; justify-content: center; z-index: 30;
	}
	.modal-box {
		background: var(--panel); border: 1px solid var(--border); border-radius: 10px;
		padding: 20px; width: 420px; max-width: 90vw;
	}
	.modal-box h2 { margin-top: 0; font-size: 16px; }
	.modal-box input[type=text] { margin-bottom: 12px; }
	.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 8px; }
	.modal-fullscreen { width: 90vw; height: 90vh; max-width: none; display: flex; flex-direction: column; }
	.modal-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
	.modal-header h2 { margin: 0; }
	.view-content { flex: 1; overflow-y: auto; background: #0c0e14; border: 1px solid var(--border); border-radius: 8px; padding: 16px; }
	.view-content pre { white-space: pre-wrap; word-wrap: break-word; font-family: inherit; margin: 0; }
	.view-content h1, .view-content h2, .view-content h3 { margin-top: 1em; }
	.view-content code { background: rgba(255,255,255,0.08); padding: 1px 5px; border-radius: 4px; font-family: monospace; }
	.view-content pre.code-block { background: rgba(255,255,255,0.08); padding: 10px; border-radius: 6px; overflow-x: auto; white-space: pre; }
</style>
```

- [ ] **Step 2: Add the menu and modal markup**

Find this exact text (the end of `<main>` and the start of `<script>`, currently lines 175-177):

```html
</main>

<script>
```

Replace it with:

```html
</main>

<div id="docMenu" class="dropdown-menu hidden">
	<button data-action="view">View file</button>
	<button data-action="rename">Rename this file</button>
	<button data-action="delete" class="danger">Delete document</button>
</div>

<div id="viewOverlay" class="modal-overlay hidden">
	<div class="modal-box modal-fullscreen">
		<div class="modal-header">
			<h2 id="viewTitle"></h2>
			<button class="secondary" id="viewCloseBtn">Close</button>
		</div>
		<div id="viewContent" class="view-content"></div>
	</div>
</div>

<div id="renameOverlay" class="modal-overlay hidden">
	<div class="modal-box">
		<h2>Rename document</h2>
		<label for="renameInput">Title</label>
		<input type="text" id="renameInput">
		<div id="renameError" class="error-text"></div>
		<div class="modal-actions">
			<button class="secondary" id="renameCancelBtn">Cancel</button>
			<button id="renameConfirmBtn">Save</button>
		</div>
	</div>
</div>

<div id="deleteOverlay" class="modal-overlay hidden">
	<div class="modal-box">
		<h2>Delete document?</h2>
		<p class="muted">Are you sure you want to delete this document?</p>
		<div id="deleteError" class="error-text"></div>
		<div class="modal-actions">
			<button class="secondary" id="deleteNoBtn">No</button>
			<button id="deleteYesBtn">Yes</button>
		</div>
	</div>
</div>

<script>
```

- [ ] **Step 3: Add a 3-dot button to each document row and cache the fetched documents**

Find this exact block (the `refreshDocs()` function and the line right after it, currently lines 303-333):

```javascript
	// --- Document list + index status ---
	async function refreshDocs() {
		const list = document.getElementById('docList');
		const docs = await fetch(`/api/projects/${projectId}/documents`).then(r => r.json());
		if (docs.length === 0) {
			list.innerHTML = '<p class="muted">No documents yet.</p>';
			return;
		}
		list.innerHTML = '';
		for (const doc of docs) {
			const status = await fetch(`/api/projects/${projectId}/documents/${doc.id}/index-status`).then(r => r.json());
			const row = document.createElement('div');
			row.className = 'doc-row';
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
			list.appendChild(row);
		}
		list.querySelectorAll('[data-summarize]').forEach(btn => {
			btn.addEventListener('click', () => summarize(btn.dataset.summarize));
		});
	}
	document.getElementById('refreshDocsBtn').addEventListener('click', refreshDocs);
```

Replace it with:

```javascript
	// --- Document list + index status ---
	let currentDocs = [];

	async function refreshDocs() {
		const list = document.getElementById('docList');
		const docs = await fetch(`/api/projects/${projectId}/documents`).then(r => r.json());
		currentDocs = docs;
		if (docs.length === 0) {
			list.innerHTML = '<p class="muted">No documents yet.</p>';
			return;
		}
		list.innerHTML = '';
		for (const doc of docs) {
			const status = await fetch(`/api/projects/${projectId}/documents/${doc.id}/index-status`).then(r => r.json());
			const row = document.createElement('div');
			row.className = 'doc-row';
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
					<button class="secondary menu-btn" data-menu-btn="${doc.id}">&#8942;</button>
				</div>`;
			list.appendChild(row);
		}
		list.querySelectorAll('[data-summarize]').forEach(btn => {
			btn.addEventListener('click', () => summarize(btn.dataset.summarize));
		});
		list.querySelectorAll('[data-menu-btn]').forEach(btn => {
			btn.addEventListener('click', (event) => openDocMenu(event, btn.dataset.menuBtn));
		});
	}
	document.getElementById('refreshDocsBtn').addEventListener('click', refreshDocs);
```

- [ ] **Step 4: Add the menu/view/rename/delete JavaScript**

Find this exact line (the start of the `summarize` function, immediately after the block just replaced in Step 3):

```javascript
	async function summarize(documentId) {
```

Replace it with (this inserts a full new block of functions *before* `summarize`, then repeats the unchanged `summarize` line so the rest of the file is undisturbed):

```javascript
	// --- Document menu (View / Rename / Delete) ---
	let activeMenuDocId = null;

	function openDocMenu(event, documentId) {
		event.stopPropagation();
		const menu = document.getElementById('docMenu');
		const btnRect = event.target.getBoundingClientRect();
		menu.style.top = `${btnRect.bottom}px`;
		menu.style.left = `${btnRect.left}px`;
		activeMenuDocId = documentId;
		menu.classList.remove('hidden');
	}

	function closeDocMenu() {
		document.getElementById('docMenu').classList.add('hidden');
		activeMenuDocId = null;
	}

	document.getElementById('docMenu').addEventListener('click', async (event) => {
		const action = event.target.dataset.action;
		if (!action || !activeMenuDocId) return;
		const documentId = activeMenuDocId;
		closeDocMenu();
		if (action === 'view') await openViewOverlay(documentId);
		if (action === 'rename') openRenameOverlay(documentId);
		if (action === 'delete') openDeleteOverlay(documentId);
	});

	document.addEventListener('click', (event) => {
		const menu = document.getElementById('docMenu');
		if (!menu.classList.contains('hidden') && !menu.contains(event.target) && !event.target.classList.contains('menu-btn')) {
			closeDocMenu();
		}
	});

	// --- View ---
	async function openViewOverlay(documentId) {
		const overlay = document.getElementById('viewOverlay');
		const titleEl = document.getElementById('viewTitle');
		const contentEl = document.getElementById('viewContent');
		titleEl.textContent = 'Loading…';
		contentEl.innerHTML = '';
		overlay.classList.remove('hidden');
		try {
			const res = await fetch(`/api/projects/${projectId}/documents/${documentId}`);
			const body = await res.json();
			if (!res.ok) {
				titleEl.textContent = 'Error';
				contentEl.innerHTML = `<p class="error-text">${res.status}: ${escapeHtml(body.message || JSON.stringify(body))}</p>`;
				return;
			}
			titleEl.textContent = body.title;
			if (body.sourceType === 'MARKDOWN') {
				contentEl.innerHTML = renderMarkdown(body.content);
			} else {
				contentEl.innerHTML = `<pre>${escapeHtml(body.content)}</pre>`;
			}
		} catch (e) {
			titleEl.textContent = 'Error';
			contentEl.innerHTML = `<p class="error-text">Request failed: ${e}</p>`;
		}
	}
	document.getElementById('viewCloseBtn').addEventListener('click', () => {
		document.getElementById('viewOverlay').classList.add('hidden');
	});

	// A small, dependency-free Markdown renderer covering headers, bold,
	// italic, inline code, fenced code blocks, lists, and links - no
	// external library, per this project's "no new frontend dependency" rule.
	function renderMarkdown(text) {
		const lines = escapeHtml(text).split('\n');
		let html = '';
		let inCodeBlock = false;
		let inList = false;
		for (const line of lines) {
			if (line.startsWith('```')) {
				if (inCodeBlock) {
					html += '</pre>';
					inCodeBlock = false;
				} else {
					if (inList) { html += '</ul>'; inList = false; }
					html += '<pre class="code-block">';
					inCodeBlock = true;
				}
				continue;
			}
			if (inCodeBlock) {
				html += line + '\n';
				continue;
			}
			const headingMatch = line.match(/^(#{1,6})\s+(.*)$/);
			if (headingMatch) {
				if (inList) { html += '</ul>'; inList = false; }
				const level = headingMatch[1].length;
				html += `<h${level}>${inlineMarkdown(headingMatch[2])}</h${level}>`;
				continue;
			}
			const listMatch = line.match(/^[-*]\s+(.*)$/);
			if (listMatch) {
				if (!inList) { html += '<ul>'; inList = true; }
				html += `<li>${inlineMarkdown(listMatch[1])}</li>`;
				continue;
			}
			if (inList) { html += '</ul>'; inList = false; }
			if (line.trim() === '') {
				html += '<br>';
			} else {
				html += `<p>${inlineMarkdown(line)}</p>`;
			}
		}
		if (inList) html += '</ul>';
		if (inCodeBlock) html += '</pre>';
		return html;
	}

	function inlineMarkdown(text) {
		return text
			.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
			.replace(/\*(.+?)\*/g, '<em>$1</em>')
			.replace(/`(.+?)`/g, '<code>$1</code>')
			// NOTE (added after this plan's original code was found vulnerable to
			// attribute- and scheme-injection XSS during review): the actual final
			// version of this line in index.html's inlineMarkdown function escapes
			// URL quote characters and restricts the URL scheme. Do not copy this
			// historical snippet — read the current index.html source instead.
			.replace(/\[(.+?)\]\((.+?)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
	}

	// --- Rename ---
	function openRenameOverlay(documentId) {
		const doc = currentDocs.find(d => d.id === documentId);
		const overlay = document.getElementById('renameOverlay');
		const input = document.getElementById('renameInput');
		document.getElementById('renameError').textContent = '';
		input.value = doc.title;
		overlay.dataset.documentId = documentId;
		overlay.classList.remove('hidden');
		input.focus();
	}
	document.getElementById('renameCancelBtn').addEventListener('click', () => {
		document.getElementById('renameOverlay').classList.add('hidden');
	});
	document.getElementById('renameConfirmBtn').addEventListener('click', async () => {
		const overlay = document.getElementById('renameOverlay');
		const documentId = overlay.dataset.documentId;
		const input = document.getElementById('renameInput');
		const errorEl = document.getElementById('renameError');
		const newTitle = input.value.trim();
		errorEl.textContent = '';
		if (!newTitle) { errorEl.textContent = 'Title is required.'; return; }
		try {
			const res = await fetch(`/api/projects/${projectId}/documents/${documentId}/title`, {
				method: 'PUT',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ title: newTitle })
			});
			const body = await res.json();
			if (!res.ok) {
				errorEl.textContent = body.errors ? body.errors[0].message : (body.message || 'Rename failed.');
				return;
			}
			overlay.classList.add('hidden');
			await refreshDocs();
		} catch (e) {
			errorEl.textContent = 'Request failed: ' + e;
		}
	});

	// --- Delete ---
	function openDeleteOverlay(documentId) {
		const overlay = document.getElementById('deleteOverlay');
		document.getElementById('deleteError').textContent = '';
		overlay.dataset.documentId = documentId;
		overlay.classList.remove('hidden');
	}
	document.getElementById('deleteNoBtn').addEventListener('click', () => {
		document.getElementById('deleteOverlay').classList.add('hidden');
	});
	document.getElementById('deleteYesBtn').addEventListener('click', async () => {
		const overlay = document.getElementById('deleteOverlay');
		const documentId = overlay.dataset.documentId;
		const errorEl = document.getElementById('deleteError');
		try {
			const res = await fetch(`/api/projects/${projectId}/documents/${documentId}`, { method: 'DELETE' });
			if (!res.ok) {
				const body = await res.json().catch(() => ({}));
				errorEl.textContent = body.message || `Delete failed (${res.status}).`;
				return;
			}
			overlay.classList.add('hidden');
			await refreshDocs();
		} catch (e) {
			errorEl.textContent = 'Request failed: ' + e;
		}
	});

	async function summarize(documentId) {
```

Note: `openViewOverlay`, `openRenameOverlay`, and `openDeleteOverlay` are called from the `docMenu` click listener above their own textual definition in this block — this is safe because they are all declared with `function name() {}` syntax, which JavaScript hoists to the top of the enclosing `<script>` scope, so they are callable from anywhere in the file regardless of definition order.

- [ ] **Step 5: Manual verification**

```bash
./mvnw spring-boot:run
```

1. Open the app in a browser. Upload one Markdown file (e.g. containing `# Title`, a `**bold**` word, a bullet list, and a fenced code block) and one plain-text or PDF file.
2. Click the 3-dot button on the Markdown document's row. Confirm the menu shows "View file", "Rename this file", "Delete document", and dismisses if you click elsewhere on the page without choosing anything.
3. Click "View file" on the Markdown document. Confirm a full-screen overlay opens showing the heading rendered as an actual `<h1>`, the bold word rendered bold, the list as an actual bullet list, and the code block in a monospace block. Click "Close" and confirm the overlay disappears with the document list unchanged.
4. Click "View file" on the plain-text/PDF document. Confirm its content displays as plain preformatted text (no Markdown formatting applied).
5. Click "Rename this file" on a document. Confirm the dialog opens pre-filled with the current title. Clear the field and click "Save" — confirm a "Title is required" error appears and the dialog stays open. Enter a new title and click "Save" — confirm the dialog closes and the document list shows the new title. Ask a question that this document would answer, and confirm the returned source citation shows the *new* title (proving BR-02's re-index actually ran).
6. Click "Delete document" on a document. Confirm a confirmation dialog appears. Click "No" — confirm the dialog closes and the document is still present in the list (no network call was made; check the browser's network tab if unsure). Click "Delete document" again and click "Yes" — confirm the dialog closes, the document disappears from the list, and a subsequent question no longer returns that document as a source.

- [ ] **Step 6: Run the full suite one more time**

Run: `./mvnw test`
Expected: PASS, 254 tests, 0 failures, 0 errors (unaffected by this task — `index.html` has no automated coverage).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/static/index.html
git commit -m "feat: add a document menu (view, rename, delete) to the manual test UI"
```

---

## Completion Checklist

- [ ] `./mvnw test` passes; the 247 tests present before this plan are still among them, none deleted or weakened.
- [ ] `DocumentService.rename(...)` changes only `title` — `content`, `sourceType`, `contentHash`, `createdAt` are unchanged (proven by `renameChangesOnlyTitle`).
- [ ] Renaming publishes `DocumentUpdatedEvent`, so `DocumentIndexer`'s existing, untouched `onUpdated` listener re-indexes chunks with the new title (proven manually in Task 2 Step 5.5, since this project's DocumentIndexer has no direct rename-specific test — its existing `onUpdated` behavior was already fully tested when `updateText`/`updateFile` were built).
- [ ] No new exception type, no new `DocumentExceptionHandler` entry, no new dependency, no change to `DocumentIndexer`/`ChunkingService`/retrieval/generation code.
- [ ] The UI's 3-dot menu lets a user view (with Markdown rendering for `SourceType.MARKDOWN`, plain text otherwise), rename (with inline validation), and delete (with a Yes/No confirmation that makes zero changes on "No") a document.
- [ ] No secret appears in any committed file.
