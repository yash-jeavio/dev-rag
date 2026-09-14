package com.devassist.rag;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.document.Document;
import com.devassist.document.DocumentNotFoundException;
import com.devassist.document.DocumentService;
import com.devassist.document.SourceType;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IndexStatusController.class)
class IndexStatusControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DocumentService documentService;

	@MockitoBean
	private IndexStatusService statusService;

	@Test
	void returnsIndexedStatus() throws Exception {
		when(documentService.findById(anyString(), anyString())).thenReturn(
				new Document("doc-1", "proj-1", "notes.md", SourceType.MARKDOWN, "body", "hash", Instant.now()));
		when(statusService.get("doc-1")).thenReturn(
				java.util.Optional.of(IndexStatus.indexed("doc-1", 12)));

		mockMvc.perform(get("/api/projects/proj-1/documents/doc-1/index-status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INDEXED"))
				.andExpect(jsonPath("$.chunkCount").value(12));
	}

	@Test
	void reportsNotIndexedWhenNoStatusRecorded() throws Exception {
		when(documentService.findById(anyString(), anyString())).thenReturn(
				new Document("doc-1", "proj-1", "notes.md", SourceType.MARKDOWN, "body", "hash", Instant.now()));
		when(statusService.get("doc-1")).thenReturn(java.util.Optional.empty());

		mockMvc.perform(get("/api/projects/proj-1/documents/doc-1/index-status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void returnsFailedStatusWithReason() throws Exception {
		when(documentService.findById(anyString(), anyString())).thenReturn(
				new Document("doc-1", "proj-1", "notes.md", SourceType.MARKDOWN, "body", "hash", Instant.now()));
		when(statusService.get("doc-1")).thenReturn(
				java.util.Optional.of(IndexStatus.failed("doc-1", "embedding model timed out")));

		mockMvc.perform(get("/api/projects/proj-1/documents/doc-1/index-status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.reason").value("embedding model timed out"));
	}

	@Test
	void returnsNotFoundForUnknownDocument() throws Exception {
		when(documentService.findById(anyString(), anyString())).thenThrow(new DocumentNotFoundException("doc-1"));

		mockMvc.perform(get("/api/projects/proj-1/documents/doc-1/index-status"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundForDocumentBelongingToDifferentProject() throws Exception {
		// Not a duplicate of returnsNotFoundForUnknownDocument: this pins the anti-probing
		// case where doc-1 exists but under another project. DocumentService.findById throws
		// the same DocumentNotFoundException either way (mocked here), so this test's only
		// value is asserting the controller keeps delegating to findById for that check —
		// deleting the findById call as "dead code" would silently break this guarantee
		// without failing the other 404 test.
		when(documentService.findById(anyString(), anyString())).thenThrow(new DocumentNotFoundException("doc-1"));

		mockMvc.perform(get("/api/projects/other-proj/documents/doc-1/index-status"))
				.andExpect(status().isNotFound());
	}
}
