package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.document.DocumentNotFoundException;
import com.devassist.project.ProjectNotFoundException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SummarizationController.class)
class SummarizationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SummarizationService summarizationService;

	@Test
	void returnsSummary() throws Exception {
		when(summarizationService.summarize(anyString(), anyString(), any()))
				.thenReturn(new SummaryResponse("doc-1", "notes.md", "a summary", 42, false, 5));

		mockMvc.perform(post("/api/projects/proj-1/documents/doc-1/summarize")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"instruction\":\"focus on risks\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary").value("a summary"))
				.andExpect(jsonPath("$.truncated").value(false));
	}

	// The instruction is optional: an absent body must still reach the service,
	// with a null instruction rather than a 400.
	@Test
	void allowsAnAbsentRequestBody() throws Exception {
		when(summarizationService.summarize(anyString(), anyString(), any()))
				.thenReturn(new SummaryResponse("doc-1", "notes.md", "a summary", 42, false, 5));

		mockMvc.perform(post("/api/projects/proj-1/documents/doc-1/summarize"))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsAnInstructionOverTheSizeLimit() throws Exception {
		String tooLong = "x".repeat(501);

		mockMvc.perform(post("/api/projects/proj-1/documents/doc-1/summarize")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"instruction\":\"" + tooLong + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsNotFoundForUnknownDocument() throws Exception {
		when(summarizationService.summarize(anyString(), anyString(), any()))
				.thenThrow(new DocumentNotFoundException("doc-x"));

		mockMvc.perform(post("/api/projects/proj-1/documents/doc-x/summarize"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsNotFoundForUnknownProject() throws Exception {
		when(summarizationService.summarize(anyString(), anyString(), any()))
				.thenThrow(new ProjectNotFoundException("proj-x"));

		mockMvc.perform(post("/api/projects/proj-x/documents/doc-1/summarize"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsServiceUnavailableWhenTheAiProviderFails() throws Exception {
		when(summarizationService.summarize(anyString(), anyString(), any()))
				.thenThrow(new NonTransientAiException("model unreachable"));

		mockMvc.perform(post("/api/projects/proj-1/documents/doc-1/summarize"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("AI provider unavailable: model unreachable"));
	}
}
