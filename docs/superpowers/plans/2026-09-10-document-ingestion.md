# Document Ingestion Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a client ingest documents (file upload or raw text) into a `Project`, extract their plain-text content, and manage them with full CRUD (create, read, update, delete) so later RAG stages have stable document content to consume.

**Architecture:** A new `com.devassist.document` package, structured exactly like the existing `com.devassist.project` package: thin `@RestController`, an in-memory `@Service` backed by a `ConcurrentHashMap`, a per-controller `@RestControllerAdvice`, and immutable records for domain/DTOs. A small `DocumentTextExtractor` component isolates the one piece of real logic (turning file bytes into plain text) from the CRUD service.

**Tech Stack:** Java 17, Spring Boot 4.1.1 (Web MVC, Validation), Apache PDFBox 3.0.8 (new dependency, PDF text extraction), JUnit 5 + AssertJ + Spring Boot Test (`@WebMvcTest`, `MockMvc`, `MockitoBean`).

**Spec:** `specs/document-ingestion.md`

## Global Constraints

- Ingestion only — no chunking, embeddings, vector storage, retrieval, Docker, or Postgres/PGVector in this plan.
- Only new dependency: `org.apache.pdfbox:pdfbox`, version `3.0.8` pinned explicitly (not in the Spring Boot BOM).
- Size limit is 10 MB (`10 * 1024 * 1024` = `10,485,760` bytes) on both the file-upload path and the raw-text path.
- `spring.servlet.multipart.max-file-size` and `spring.servlet.multipart.max-request-size` set to `10MB`.
- Constructor injection only; thin controllers; immutable records for request/response DTOs.
- Storage is the existing in-memory `ConcurrentHashMap` pattern — no database or persistence layer.
- Do not modify the `project` package beyond calling its existing public `ProjectService.findById`.
- A document is not found (404) if its id exists but its `projectId` doesn't match the path's `projectId`.

---

### Task 1: PDF text extraction

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/devassist/document/SourceType.java`
- Create: `src/main/java/com/devassist/document/ExtractedContent.java`
- Create: `src/main/java/com/devassist/document/InvalidDocumentException.java`
- Create: `src/main/java/com/devassist/document/DocumentTextExtractor.java`
- Test: `src/test/java/com/devassist/document/DocumentTextExtractorTest.java`

**Interfaces:**
- Produces: `SourceType` enum with values `TEXT`, `MARKDOWN`, `PDF`. `ExtractedContent(SourceType sourceType, String text)` record. `InvalidDocumentException(String message)` and `InvalidDocumentException(String message, Throwable cause)`. `DocumentTextExtractor.extract(String filename, byte[] content): ExtractedContent` — infers `SourceType` from the filename extension (`.txt`→`TEXT`, `.md`→`MARKDOWN`, `.pdf`→`PDF`) and throws `InvalidDocumentException` for any other extension or for a file that fails to parse.

- [ ] **Step 1: Add the PDFBox dependency to `pom.xml`**

Add this dependency alongside the existing `spring-boot-starter-webmvc` dependency (before the `<dependency>` block for `spring-boot-starter-validation-test`):

```xml
		<dependency>
			<groupId>org.apache.pdfbox</groupId>
			<artifactId>pdfbox</artifactId>
			<version>3.0.8</version>
		</dependency>
```

- [ ] **Step 2: Write the failing test**

Create `src/test/java/com/devassist/document/DocumentTextExtractorTest.java`:

```java
package com.devassist.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {

	private final DocumentTextExtractor extractor = new DocumentTextExtractor();

	@Test
	void extractsPlainTextFile() {
		ExtractedContent result = extractor.extract("notes.txt", "Hello world".getBytes(StandardCharsets.UTF_8));

		assertThat(result.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(result.text()).isEqualTo("Hello world");
	}

	@Test
	void extractsMarkdownFile() {
		ExtractedContent result = extractor.extract("README.md", "# Title".getBytes(StandardCharsets.UTF_8));

		assertThat(result.sourceType()).isEqualTo(SourceType.MARKDOWN);
		assertThat(result.text()).isEqualTo("# Title");
	}

	@Test
	void extractsPdfFile() throws IOException {
		byte[] pdfBytes = samplePdfBytes("Hello PDF");

		ExtractedContent result = extractor.extract("sample.pdf", pdfBytes);

		assertThat(result.sourceType()).isEqualTo(SourceType.PDF);
		assertThat(result.text()).contains("Hello PDF");
	}

	@Test
	void throwsForUnsupportedExtension() {
		assertThatThrownBy(() -> extractor.extract("archive.zip", new byte[] { 1, 2, 3 }))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void throwsForCorruptPdf() {
		assertThatThrownBy(() -> extractor.extract("broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(InvalidDocumentException.class);
	}

	private static byte[] samplePdfBytes(String text) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
				contentStream.beginText();
				contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				contentStream.newLineAtOffset(50, 700);
				contentStream.showText(text);
				contentStream.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
	}
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw test -Dtest=DocumentTextExtractorTest`
Expected: FAIL to compile — `SourceType`, `ExtractedContent`, `InvalidDocumentException`, and `DocumentTextExtractor` don't exist yet.

- [ ] **Step 4: Write the minimal implementation**

Create `src/main/java/com/devassist/document/SourceType.java`:

```java
package com.devassist.document;

public enum SourceType {
	TEXT,
	MARKDOWN,
	PDF
}
```

Create `src/main/java/com/devassist/document/ExtractedContent.java`:

```java
package com.devassist.document;

public record ExtractedContent(SourceType sourceType, String text) {
}
```

Create `src/main/java/com/devassist/document/InvalidDocumentException.java`:

```java
package com.devassist.document;

public class InvalidDocumentException extends RuntimeException {

	public InvalidDocumentException(String message) {
		super(message);
	}

	public InvalidDocumentException(String message, Throwable cause) {
		super(message, cause);
	}
}
```

Create `src/main/java/com/devassist/document/DocumentTextExtractor.java`:

```java
package com.devassist.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

	public ExtractedContent extract(String filename, byte[] content) {
		String extension = extensionOf(filename);
		return switch (extension) {
			case "txt" -> new ExtractedContent(SourceType.TEXT, new String(content, StandardCharsets.UTF_8));
			case "md" -> new ExtractedContent(SourceType.MARKDOWN, new String(content, StandardCharsets.UTF_8));
			case "pdf" -> new ExtractedContent(SourceType.PDF, extractPdfText(filename, content));
			default -> throw new InvalidDocumentException("Unsupported file type: " + filename);
		};
	}

	private String extractPdfText(String filename, byte[] content) {
		try (PDDocument document = Loader.loadPDF(content)) {
			return new PDFTextStripper().getText(document);
		}
		catch (IOException e) {
			throw new InvalidDocumentException("Unable to extract text from PDF: " + filename, e);
		}
	}

	private String extensionOf(String filename) {
		int dotIndex = filename == null ? -1 : filename.lastIndexOf('.');
		if (dotIndex < 0 || dotIndex == filename.length() - 1) {
			throw new InvalidDocumentException("Unsupported file type: " + filename);
		}
		return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=DocumentTextExtractorTest`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add pom.xml src/main/java/com/devassist/document/SourceType.java \
	src/main/java/com/devassist/document/ExtractedContent.java \
	src/main/java/com/devassist/document/InvalidDocumentException.java \
	src/main/java/com/devassist/document/DocumentTextExtractor.java \
	src/test/java/com/devassist/document/DocumentTextExtractorTest.java
git commit -m "Add document text extraction for txt/md/pdf"
```

---

### Task 2: Document domain and create/read service

**Files:**
- Create: `src/main/java/com/devassist/document/Document.java`
- Create: `src/main/java/com/devassist/document/DocumentResponse.java`
- Create: `src/main/java/com/devassist/document/DocumentNotFoundException.java`
- Create: `src/main/java/com/devassist/document/IngestTextRequest.java`
- Create: `src/main/java/com/devassist/document/DocumentService.java`
- Test: `src/test/java/com/devassist/document/DocumentServiceTest.java`

**Interfaces:**
- Consumes: `DocumentTextExtractor.extract(String, byte[]): ExtractedContent`, `ExtractedContent.sourceType()`/`.text()`, `SourceType`, `InvalidDocumentException` (Task 1); `com.devassist.project.ProjectService.findById(String): Project` (throws `ProjectNotFoundException`) — existing, unmodified.
- Produces: `Document(String id, String projectId, String title, SourceType sourceType, String content, Instant createdAt)`. `DocumentResponse.from(Document): DocumentResponse`. `DocumentService.ingestFile(String projectId, String filename, byte[] fileBytes): Document`. `DocumentService.ingestText(String projectId, IngestTextRequest request): Document`. `DocumentService.findById(String projectId, String documentId): Document` (throws `DocumentNotFoundException`). Task 3 will add `updateFile`/`updateText`/`delete` to this same class and reuse `findById` internally.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/devassist/document/DocumentServiceTest.java`:

```java
package com.devassist.document;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectNotFoundException;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

	private final ProjectService projectService = new ProjectService();
	private final DocumentTextExtractor textExtractor = new DocumentTextExtractor();
	private final DocumentService documentService = new DocumentService(projectService, textExtractor);

	private String existingProjectId;

	@BeforeEach
	void createProject() {
		Project project = projectService
				.create(new CreateProjectRequest("DevAssist", null, "Java", "https://github.com/example/repo"));
		existingProjectId = project.id();
	}

	@Test
	void ingestFileStoresPlainTextDocument() {
		Document document = documentService.ingestFile(existingProjectId, "notes.txt",
				"Hello world".getBytes(StandardCharsets.UTF_8));

		assertThat(document.id()).isNotBlank();
		assertThat(document.projectId()).isEqualTo(existingProjectId);
		assertThat(document.title()).isEqualTo("notes.txt");
		assertThat(document.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(document.content()).isEqualTo("Hello world");
	}

	@Test
	void ingestFileAssignsMarkdownSourceTypeFromExtension() {
		Document document = documentService.ingestFile(existingProjectId, "README.md",
				"# Title".getBytes(StandardCharsets.UTF_8));

		assertThat(document.sourceType()).isEqualTo(SourceType.MARKDOWN);
	}

	@Test
	void ingestFileRejectsEmptyFile() {
		assertThatThrownBy(() -> documentService.ingestFile(existingProjectId, "empty.txt", new byte[0]))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void ingestFileRejectsFileOverTenMegabytes() {
		byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];

		assertThatThrownBy(() -> documentService.ingestFile(existingProjectId, "big.txt", tooLarge))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void ingestFileThrowsWhenProjectDoesNotExist() {
		assertThatThrownBy(() -> documentService.ingestFile("unknown-project", "notes.txt",
				"Hello".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ProjectNotFoundException.class);
	}

	@Test
	void ingestTextStoresDocumentWithTextSourceType() {
		Document document = documentService.ingestText(existingProjectId,
				new IngestTextRequest("Meeting Notes", "We discussed the roadmap."));

		assertThat(document.title()).isEqualTo("Meeting Notes");
		assertThat(document.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(document.content()).isEqualTo("We discussed the roadmap.");
	}

	@Test
	void ingestTextRejectsContentOverTenMegabytes() {
		String tooLarge = "a".repeat(10 * 1024 * 1024 + 1);

		assertThatThrownBy(
				() -> documentService.ingestText(existingProjectId, new IngestTextRequest("Big", tooLarge)))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void ingestTextThrowsWhenProjectDoesNotExist() {
		assertThatThrownBy(() -> documentService.ingestText("unknown-project", new IngestTextRequest("Title", "Body")))
				.isInstanceOf(ProjectNotFoundException.class);
	}

	@Test
	void findByIdReturnsPreviouslyIngestedDocument() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		Document found = documentService.findById(existingProjectId, created.id());

		assertThat(found).isEqualTo(created);
	}

	@Test
	void findByIdThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.findById(existingProjectId, "unknown-id"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void findByIdThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.findById(otherProject.id(), created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentServiceTest`
Expected: FAIL to compile — `Document`, `DocumentResponse`, `DocumentNotFoundException`, `IngestTextRequest`, and `DocumentService` don't exist yet.

- [ ] **Step 3: Write the minimal implementation**

Create `src/main/java/com/devassist/document/Document.java`:

```java
package com.devassist.document;

import java.time.Instant;

public record Document(
		String id,
		String projectId,
		String title,
		SourceType sourceType,
		String content,
		Instant createdAt
) {
}
```

Create `src/main/java/com/devassist/document/DocumentResponse.java`:

```java
package com.devassist.document;

import java.time.Instant;

public record DocumentResponse(String id, String projectId, String title, SourceType sourceType, String content,
		Instant createdAt) {

	public static DocumentResponse from(Document document) {
		return new DocumentResponse(document.id(), document.projectId(), document.title(), document.sourceType(),
				document.content(), document.createdAt());
	}
}
```

Create `src/main/java/com/devassist/document/DocumentNotFoundException.java`:

```java
package com.devassist.document;

public class DocumentNotFoundException extends RuntimeException {

	public DocumentNotFoundException(String id) {
		super("Document not found: " + id);
	}
}
```

Create `src/main/java/com/devassist/document/IngestTextRequest.java`:

```java
package com.devassist.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IngestTextRequest(
		@NotBlank @Size(max = 200) String title,
		@NotBlank String content) {
}
```

Create `src/main/java/com/devassist/document/DocumentService.java`:

```java
package com.devassist.document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.devassist.project.ProjectService;

@Service
public class DocumentService {

	private static final long MAX_CONTENT_BYTES = 10L * 1024 * 1024;

	private final Map<String, Document> documents = new ConcurrentHashMap<>();
	private final ProjectService projectService;
	private final DocumentTextExtractor textExtractor;

	public DocumentService(ProjectService projectService, DocumentTextExtractor textExtractor) {
		this.projectService = projectService;
		this.textExtractor = textExtractor;
	}

	public Document ingestFile(String projectId, String filename, byte[] fileBytes) {
		projectService.findById(projectId);
		validateFile(fileBytes);
		ExtractedContent extracted = textExtractor.extract(filename, fileBytes);
		Document document = new Document(UUID.randomUUID().toString(), projectId, filename, extracted.sourceType(),
				extracted.text(), Instant.now());
		documents.put(document.id(), document);
		return document;
	}

	public Document ingestText(String projectId, IngestTextRequest request) {
		projectService.findById(projectId);
		validateContentSize(request.content());
		Document document = new Document(UUID.randomUUID().toString(), projectId, request.title(), SourceType.TEXT,
				request.content(), Instant.now());
		documents.put(document.id(), document);
		return document;
	}

	public Document findById(String projectId, String documentId) {
		Document document = documents.get(documentId);
		if (document == null || !document.projectId().equals(projectId)) {
			throw new DocumentNotFoundException(documentId);
		}
		return document;
	}

	private void validateFile(byte[] fileBytes) {
		if (fileBytes.length == 0) {
			throw new InvalidDocumentException("File must not be empty");
		}
		if (fileBytes.length > MAX_CONTENT_BYTES) {
			throw new InvalidDocumentException("File exceeds the maximum allowed size of 10MB");
		}
	}

	private void validateContentSize(String content) {
		if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
			throw new InvalidDocumentException("Content exceeds the maximum allowed size of 10MB");
		}
	}
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentServiceTest`
Expected: PASS (11 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/document/Document.java \
	src/main/java/com/devassist/document/DocumentResponse.java \
	src/main/java/com/devassist/document/DocumentNotFoundException.java \
	src/main/java/com/devassist/document/IngestTextRequest.java \
	src/main/java/com/devassist/document/DocumentService.java \
	src/test/java/com/devassist/document/DocumentServiceTest.java
git commit -m "Add document create and read to DocumentService"
```

---

### Task 3: Update and delete service operations

**Files:**
- Modify: `src/main/java/com/devassist/document/DocumentService.java`
- Modify: `src/test/java/com/devassist/document/DocumentServiceTest.java`

**Interfaces:**
- Consumes: everything from Task 2, including the private `validateFile`/`validateContentSize` helpers and `findById` (reused for existence + ownership checks).
- Produces: `DocumentService.updateFile(String projectId, String documentId, String filename, byte[] fileBytes): Document`. `DocumentService.updateText(String projectId, String documentId, IngestTextRequest request): Document`. `DocumentService.delete(String projectId, String documentId): void`. All three throw `DocumentNotFoundException` when the document doesn't exist or belongs to a different project.

- [ ] **Step 1: Write the failing tests**

Add these test methods inside the `DocumentServiceTest` class (before the closing brace):

```java

	@Test
	void updateFileReplacesContentAndPreservesIdAndCreatedAt() {
		Document created = documentService.ingestFile(existingProjectId, "notes.txt",
				"Original".getBytes(StandardCharsets.UTF_8));

		Document updated = documentService.updateFile(existingProjectId, created.id(), "revised.md",
				"Revised".getBytes(StandardCharsets.UTF_8));

		assertThat(updated.id()).isEqualTo(created.id());
		assertThat(updated.projectId()).isEqualTo(existingProjectId);
		assertThat(updated.title()).isEqualTo("revised.md");
		assertThat(updated.sourceType()).isEqualTo(SourceType.MARKDOWN);
		assertThat(updated.content()).isEqualTo("Revised");
		assertThat(updated.createdAt()).isEqualTo(created.createdAt());
		assertThat(documentService.findById(existingProjectId, created.id())).isEqualTo(updated);
	}

	@Test
	void updateFileThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.updateFile(existingProjectId, "unknown-id", "notes.txt",
				"Body".getBytes(StandardCharsets.UTF_8))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void updateFileThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestFile(existingProjectId, "notes.txt",
				"Original".getBytes(StandardCharsets.UTF_8));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.updateFile(otherProject.id(), created.id(), "notes.txt",
				"Body".getBytes(StandardCharsets.UTF_8))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void updateTextReplacesTitleAndContent() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		Document updated = documentService.updateText(existingProjectId, created.id(),
				new IngestTextRequest("Renamed", "Updated body"));

		assertThat(updated.id()).isEqualTo(created.id());
		assertThat(updated.title()).isEqualTo("Renamed");
		assertThat(updated.content()).isEqualTo("Updated body");
		assertThat(updated.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(updated.createdAt()).isEqualTo(created.createdAt());
	}

	@Test
	void updateTextThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.updateText(existingProjectId, "unknown-id",
				new IngestTextRequest("Title", "Body"))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteRemovesDocument() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		documentService.delete(existingProjectId, created.id());

		assertThatThrownBy(() -> documentService.findById(existingProjectId, created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.delete(existingProjectId, "unknown-id"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.delete(otherProject.id(), created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentServiceTest`
Expected: FAIL to compile — `updateFile`, `updateText`, and `delete` don't exist on `DocumentService` yet.

- [ ] **Step 3: Write the minimal implementation**

Add these methods to `DocumentService` (after `findById`, before the private helpers):

```java

	public Document updateFile(String projectId, String documentId, String filename, byte[] fileBytes) {
		Document existing = findById(projectId, documentId);
		validateFile(fileBytes);
		ExtractedContent extracted = textExtractor.extract(filename, fileBytes);
		Document updated = new Document(existing.id(), existing.projectId(), filename, extracted.sourceType(),
				extracted.text(), existing.createdAt());
		documents.put(updated.id(), updated);
		return updated;
	}

	public Document updateText(String projectId, String documentId, IngestTextRequest request) {
		Document existing = findById(projectId, documentId);
		validateContentSize(request.content());
		Document updated = new Document(existing.id(), existing.projectId(), request.title(), SourceType.TEXT,
				request.content(), existing.createdAt());
		documents.put(updated.id(), updated);
		return updated;
	}

	public void delete(String projectId, String documentId) {
		Document existing = findById(projectId, documentId);
		documents.remove(existing.id());
	}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentServiceTest`
Expected: PASS (19 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/devassist/document/DocumentService.java src/test/java/com/devassist/document/DocumentServiceTest.java
git commit -m "Add document update and delete to DocumentService"
```

---

### Task 4: Create/read REST endpoints

**Files:**
- Modify: `src/main/resources/application.properties`
- Create: `src/main/java/com/devassist/document/DocumentController.java`
- Create: `src/main/java/com/devassist/document/DocumentExceptionHandler.java`
- Test: `src/test/java/com/devassist/document/DocumentControllerTest.java`

**Interfaces:**
- Consumes: `DocumentService.ingestFile`, `.ingestText`, `.findById` (Task 2); `Document`, `DocumentResponse.from`, `IngestTextRequest`, `DocumentNotFoundException`, `InvalidDocumentException` (Tasks 1–2); `com.devassist.project.ProjectNotFoundException` — existing, unmodified.
- Produces: `POST /api/projects/{projectId}/documents` (multipart, part `file`) → 201. `POST /api/projects/{projectId}/documents/text` (JSON `IngestTextRequest`) → 201. `GET /api/projects/{projectId}/documents/{documentId}` → 200. `DocumentExceptionHandler` mapping `MethodArgumentNotValidException`→400, `ProjectNotFoundException`→404, `DocumentNotFoundException`→404, `InvalidDocumentException`→400, `MaxUploadSizeExceededException`→400. Task 5 adds `PUT`/`DELETE` methods to the same `DocumentController` class; no new exception handlers are needed for that.

- [ ] **Step 1: Configure multipart size limits**

Update `src/main/resources/application.properties` to:

```properties
spring.application.name=devassist
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/com/devassist/document/DocumentControllerTest.java`:

```java
package com.devassist.document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.project.ProjectNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DocumentService documentService;

	@Test
	void createFromFileReturnsCreatedWithLocationAndBody() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document document = new Document("doc-1", "project-1", "notes.txt", SourceType.TEXT, "Hello world",
				createdAt);
		given(documentService.ingestFile(eq("project-1"), eq("notes.txt"), any(byte[].class))).willReturn(document);

		MockMultipartFile file = new MockMultipartFile("file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
				"Hello world".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/api/projects/{projectId}/documents", "project-1").file(file))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/projects/project-1/documents/doc-1"))
				.andExpect(jsonPath("$.id").value("doc-1"))
				.andExpect(jsonPath("$.title").value("notes.txt"))
				.andExpect(jsonPath("$.sourceType").value("TEXT"))
				.andExpect(jsonPath("$.content").value("Hello world"));
	}

	@Test
	void createFromFileReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
		given(documentService.ingestFile(eq("missing"), any(), any(byte[].class)))
				.willThrow(new ProjectNotFoundException("missing"));

		MockMultipartFile file = new MockMultipartFile("file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
				"Hello world".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/api/projects/{projectId}/documents", "missing").file(file))
				.andExpect(status().isNotFound());
	}

	@Test
	void createFromFileReturnsBadRequestForInvalidDocument() throws Exception {
		given(documentService.ingestFile(eq("project-1"), any(), any(byte[].class)))
				.willThrow(new InvalidDocumentException("Unsupported file type: archive.zip"));

		MockMultipartFile file = new MockMultipartFile("file", "archive.zip", MediaType.APPLICATION_OCTET_STREAM_VALUE,
				"data".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/api/projects/{projectId}/documents", "project-1").file(file))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void createFromTextReturnsCreatedWithLocationAndBody() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document document = new Document("doc-1", "project-1", "Meeting Notes", SourceType.TEXT, "Body text",
				createdAt);
		given(documentService.ingestText(eq("project-1"), any(IngestTextRequest.class))).willReturn(document);

		mockMvc.perform(post("/api/projects/{projectId}/documents/text", "project-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"title": "Meeting Notes",
							"content": "Body text"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/projects/project-1/documents/doc-1"))
				.andExpect(jsonPath("$.title").value("Meeting Notes"));
	}

	@Test
	void createFromTextReturnsBadRequestWhenTitleIsMissing() throws Exception {
		mockMvc.perform(post("/api/projects/{projectId}/documents/text", "project-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"content": "Body text"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"));
	}

	@Test
	void getByIdReturnsOkWhenDocumentExists() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document document = new Document("doc-1", "project-1", "notes.txt", SourceType.TEXT, "Hello world",
				createdAt);
		given(documentService.findById("project-1", "doc-1")).willReturn(document);

		mockMvc.perform(get("/api/projects/{projectId}/documents/{documentId}", "project-1", "doc-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("doc-1"));
	}

	@Test
	void getByIdReturnsNotFoundWhenDocumentDoesNotExist() throws Exception {
		given(documentService.findById("project-1", "missing")).willThrow(new DocumentNotFoundException("missing"));

		mockMvc.perform(get("/api/projects/{projectId}/documents/{documentId}", "project-1", "missing"))
				.andExpect(status().isNotFound());
	}
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: FAIL to compile — `DocumentController` doesn't exist yet.

- [ ] **Step 4: Write the minimal implementation**

Create `src/main/java/com/devassist/document/DocumentController.java`:

```java
package com.devassist.document;

import java.io.IOException;
import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
public class DocumentController {

	private final DocumentService documentService;

	public DocumentController(DocumentService documentService) {
		this.documentService = documentService;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<DocumentResponse> create(@PathVariable String projectId,
			@RequestParam("file") MultipartFile file) throws IOException {
		Document document = documentService.ingestFile(projectId, file.getOriginalFilename(), file.getBytes());
		return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/documents/" + document.id()))
				.body(DocumentResponse.from(document));
	}

	@PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentResponse> createFromText(@PathVariable String projectId,
			@Valid @RequestBody IngestTextRequest request) {
		Document document = documentService.ingestText(projectId, request);
		return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/documents/" + document.id()))
				.body(DocumentResponse.from(document));
	}

	@GetMapping("/{documentId}")
	public ResponseEntity<DocumentResponse> getById(@PathVariable String projectId,
			@PathVariable String documentId) {
		Document document = documentService.findById(projectId, documentId);
		return ResponseEntity.ok(DocumentResponse.from(document));
	}
}
```

Create `src/main/java/com/devassist/document/DocumentExceptionHandler.java`:

```java
package com.devassist.document;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.devassist.project.ProjectNotFoundException;

@RestControllerAdvice(assignableTypes = DocumentController.class)
public class DocumentExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(fieldError -> Map.of("field", fieldError.getField(), "message", fieldError.getDefaultMessage()))
				.toList();
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "errors", errors);
	}

	@ExceptionHandler(ProjectNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, Object> handleProjectNotFound(ProjectNotFoundException ex) {
		return Map.of("status", HttpStatus.NOT_FOUND.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(DocumentNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public Map<String, Object> handleDocumentNotFound(DocumentNotFoundException ex) {
		return Map.of("status", HttpStatus.NOT_FOUND.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(InvalidDocumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleInvalidDocument(InvalidDocumentException ex) {
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "message", ex.getMessage());
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public Map<String, Object> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
		return Map.of("status", HttpStatus.BAD_REQUEST.value(), "message",
				"Uploaded file exceeds the maximum allowed size");
	}
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: PASS (6 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.properties src/main/java/com/devassist/document/DocumentController.java \
	src/main/java/com/devassist/document/DocumentExceptionHandler.java \
	src/test/java/com/devassist/document/DocumentControllerTest.java
git commit -m "Add create and read REST endpoints for documents"
```

---

### Task 5: Update/delete REST endpoints

**Files:**
- Modify: `src/main/java/com/devassist/document/DocumentController.java`
- Modify: `src/test/java/com/devassist/document/DocumentControllerTest.java`

**Interfaces:**
- Consumes: `DocumentService.updateFile`, `.updateText`, `.delete` (Task 3); `DocumentExceptionHandler` from Task 4 (unchanged — no new exception types are introduced).
- Produces: `PUT /api/projects/{projectId}/documents/{documentId}` (multipart, part `file`) → 200. `PUT /api/projects/{projectId}/documents/{documentId}/text` (JSON `IngestTextRequest`) → 200. `DELETE /api/projects/{projectId}/documents/{documentId}` → 204.

- [ ] **Step 1: Write the failing tests**

Add these imports to `DocumentControllerTest` (alongside the existing static imports):

```java
import org.springframework.http.HttpMethod;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
```

Add these test methods inside the `DocumentControllerTest` class (before the closing brace):

```java

	@Test
	void updateReturnsOkWithUpdatedBody() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document updated = new Document("doc-1", "project-1", "revised.txt", SourceType.TEXT, "Revised content",
				createdAt);
		given(documentService.updateFile(eq("project-1"), eq("doc-1"), eq("revised.txt"), any(byte[].class)))
				.willReturn(updated);

		MockMultipartFile file = new MockMultipartFile("file", "revised.txt", MediaType.TEXT_PLAIN_VALUE,
				"Revised content".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart(HttpMethod.PUT, "/api/projects/{projectId}/documents/{documentId}", "project-1",
				"doc-1").file(file))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("revised.txt"))
				.andExpect(jsonPath("$.content").value("Revised content"));
	}

	@Test
	void updateReturnsNotFoundWhenDocumentDoesNotExist() throws Exception {
		given(documentService.updateFile(eq("project-1"), eq("missing"), any(), any(byte[].class)))
				.willThrow(new DocumentNotFoundException("missing"));

		MockMultipartFile file = new MockMultipartFile("file", "revised.txt", MediaType.TEXT_PLAIN_VALUE,
				"Revised content".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart(HttpMethod.PUT, "/api/projects/{projectId}/documents/{documentId}", "project-1",
				"missing").file(file))
				.andExpect(status().isNotFound());
	}

	@Test
	void updateFromTextReturnsOkWithUpdatedBody() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document updated = new Document("doc-1", "project-1", "Renamed", SourceType.TEXT, "Updated body", createdAt);
		given(documentService.updateText(eq("project-1"), eq("doc-1"), any(IngestTextRequest.class)))
				.willReturn(updated);

		mockMvc.perform(put("/api/projects/{projectId}/documents/{documentId}/text", "project-1", "doc-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"title": "Renamed",
							"content": "Updated body"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Renamed"));
	}

	@Test
	void updateFromTextReturnsBadRequestWhenTitleIsMissing() throws Exception {
		mockMvc.perform(put("/api/projects/{projectId}/documents/{documentId}/text", "project-1", "doc-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"content": "Updated body"
						}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("title"));
	}

	@Test
	void deleteReturnsNoContent() throws Exception {
		mockMvc.perform(delete("/api/projects/{projectId}/documents/{documentId}", "project-1", "doc-1"))
				.andExpect(status().isNoContent());
	}

	@Test
	void deleteReturnsNotFoundWhenDocumentDoesNotExist() throws Exception {
		doThrow(new DocumentNotFoundException("missing")).when(documentService).delete("project-1", "missing");

		mockMvc.perform(delete("/api/projects/{projectId}/documents/{documentId}", "project-1", "missing"))
				.andExpect(status().isNotFound());
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: FAIL to compile — `update`, `updateFromText`, and `delete` don't exist on `DocumentController` yet.

- [ ] **Step 3: Write the minimal implementation**

Add `PutMapping` and `DeleteMapping` to the import list in `DocumentController.java`:

```java
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
```

Add these methods to `DocumentController` (after `getById`, before the closing brace):

```java

	@PutMapping(path = "/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<DocumentResponse> update(@PathVariable String projectId, @PathVariable String documentId,
			@RequestParam("file") MultipartFile file) throws IOException {
		Document document = documentService.updateFile(projectId, documentId, file.getOriginalFilename(),
				file.getBytes());
		return ResponseEntity.ok(DocumentResponse.from(document));
	}

	@PutMapping(path = "/{documentId}/text", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentResponse> updateFromText(@PathVariable String projectId,
			@PathVariable String documentId, @Valid @RequestBody IngestTextRequest request) {
		Document document = documentService.updateText(projectId, documentId, request);
		return ResponseEntity.ok(DocumentResponse.from(document));
	}

	@DeleteMapping("/{documentId}")
	public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String documentId) {
		documentService.delete(projectId, documentId);
		return ResponseEntity.noContent().build();
	}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=DocumentControllerTest`
Expected: PASS (12 tests)

- [ ] **Step 5: Run the full test suite**

Run: `./mvnw test`
Expected: PASS (all tests across `project` and `document` packages)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/devassist/document/DocumentController.java src/test/java/com/devassist/document/DocumentControllerTest.java
git commit -m "Add update and delete REST endpoints for documents"
```
