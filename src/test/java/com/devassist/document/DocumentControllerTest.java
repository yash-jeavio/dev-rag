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
