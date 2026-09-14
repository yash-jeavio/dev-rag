package com.devassist.document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.project.ProjectNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
				"hash-1", createdAt);
		given(documentService.ingestFile(eq("project-1"), eq("notes.txt"), any(byte[].class)))
				.willReturn(new IngestResult(document, false));

		MockMultipartFile file = new MockMultipartFile("file", "notes.txt", MediaType.TEXT_PLAIN_VALUE,
				"Hello world".getBytes(StandardCharsets.UTF_8));

		mockMvc.perform(multipart("/api/projects/{projectId}/documents", "project-1").file(file))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/projects/project-1/documents/doc-1"))
				.andExpect(jsonPath("$.id").value("doc-1"))
				.andExpect(jsonPath("$.title").value("notes.txt"))
				.andExpect(jsonPath("$.sourceType").value("TEXT"))
				.andExpect(jsonPath("$.content").value("Hello world"))
				.andExpect(jsonPath("$.deduplicated").value(false));
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
				"hash-1", createdAt);
		given(documentService.ingestText(eq("project-1"), any(IngestTextRequest.class)))
				.willReturn(new IngestResult(document, false));

		mockMvc.perform(post("/api/projects/{projectId}/documents/text", "project-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"title": "Meeting Notes",
							"content": "Body text"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/projects/project-1/documents/doc-1"))
				.andExpect(jsonPath("$.title").value("Meeting Notes"))
				.andExpect(jsonPath("$.deduplicated").value(false));
	}

	@Test
	void createFromTextReturnsOkWithDeduplicatedTrueOnDuplicateContent() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document existingDocument = new Document("doc-1", "project-1", "Meeting Notes", SourceType.TEXT, "Body text",
				"hash-1", createdAt);
		given(documentService.ingestText(eq("project-1"), any(IngestTextRequest.class)))
				.willReturn(new IngestResult(existingDocument, true));

		mockMvc.perform(post("/api/projects/{projectId}/documents/text", "project-1")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{
							"title": "Meeting Notes Again",
							"content": "Body text"
						}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value("doc-1"))
				.andExpect(jsonPath("$.deduplicated").value(true));
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
				"hash-1", createdAt);
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

	@Test
	void updateReturnsOkWithUpdatedBody() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document updated = new Document("doc-1", "project-1", "revised.txt", SourceType.TEXT, "Revised content",
				"hash-1", createdAt);
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
		Document updated = new Document("doc-1", "project-1", "Renamed", SourceType.TEXT, "Updated body", "hash-1",
				createdAt);
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

	@Test
	void listReturnsOkWithDocumentsForKnownProject() throws Exception {
		Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
		Document document = new Document("doc-1", "project-1", "notes.txt", SourceType.TEXT, "Hello world",
				"hash-1", createdAt);
		given(documentService.findByProject("project-1")).willReturn(List.of(document));

		mockMvc.perform(get("/api/projects/{projectId}/documents", "project-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value("doc-1"))
				.andExpect(jsonPath("$[0].title").value("notes.txt"));
	}

	@Test
	void listReturnsNotFoundWhenProjectDoesNotExist() throws Exception {
		given(documentService.findByProject("missing")).willThrow(new ProjectNotFoundException("missing"));

		mockMvc.perform(get("/api/projects/{projectId}/documents", "missing"))
				.andExpect(status().isNotFound());
	}

	@Test
	void listReturnsEmptyArrayWhenProjectHasNoDocuments() throws Exception {
		given(documentService.findByProject("project-1")).willReturn(List.of());

		mockMvc.perform(get("/api/projects/{projectId}/documents", "project-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}
}
