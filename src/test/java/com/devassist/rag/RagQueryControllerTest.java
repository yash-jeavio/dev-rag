package com.devassist.rag;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.project.ProjectNotFoundException;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagQueryController.class)
class RagQueryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private RagQueryService queryService;

	@Test
	void returnsAnswerWithSources() throws Exception {
		when(queryService.answer(anyString(), anyString())).thenReturn(
				RagAnswerResponse.of("q", "answer [1]", RagAnswerResponse.Status.ANSWERED,
						List.of(new SourceReference("doc-1", "a.md", 0, 0.82, true, "text")), 12));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ANSWERED"))
				.andExpect(jsonPath("$.sources[0].cited").value(true));
	}

	@Test
	void rejectsBlankQuestion() throws Exception {
		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"  \"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void returnsNotFoundForUnknownProject() throws Exception {
		when(queryService.answer(anyString(), anyString()))
				.thenThrow(new ProjectNotFoundException("proj-x"));

		mockMvc.perform(post("/api/projects/proj-x/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsServiceUnavailableWhenTheAiProviderFails() throws Exception {
		when(queryService.answer(anyString(), anyString()))
				.thenThrow(new NonTransientAiException("model unreachable"));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("AI provider unavailable: model unreachable"));
	}
}
