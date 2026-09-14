package com.devassist.rag;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.document.Document;
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
}
