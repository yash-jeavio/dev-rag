package com.devassist.rag;

import java.util.List;

import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.project.ProjectNotFoundException;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
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
		when(queryService.answer(anyString(), anyString(), anyList())).thenReturn(
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
		when(queryService.answer(anyString(), anyString(), anyList()))
				.thenThrow(new ProjectNotFoundException("proj-x"));

		mockMvc.perform(post("/api/projects/proj-x/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void returnsServiceUnavailableWhenTheAiProviderFails() throws Exception {
		when(queryService.answer(anyString(), anyString(), anyList()))
				.thenThrow(new NonTransientAiException("model unreachable"));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value("AI provider unavailable: model unreachable"));
	}

	// Regression test for the task-13 finding: with no GEMINI_API_KEY, the
	// Gemini ChatModel/Client bean fails to build the first time a query
	// actually needs it (GenerationService resolves it lazily via
	// ObjectProvider - see GeminiLazyChatModelConfiguration), surfacing as a
	// BeanCreationException at that call site rather than at startup. This
	// must map to the same 503 as a live provider failure, not a bare 500.
	@Test
	void returnsServiceUnavailableWhenTheChatModelBeanFailsToBuild() throws Exception {
		when(queryService.answer(anyString(), anyString(), anyList()))
				.thenThrow(new BeanCreationException("googleGenAiClient", "Incomplete Google GenAI configuration"));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value(
						org.hamcrest.Matchers.containsString("Incomplete Google GenAI configuration")));
	}

	// Regression test for a real failure observed against the live Gemini API:
	// a retired/invalid model name comes back as com.google.genai.errors.
	// ClientException, wrapped by Spring AI's ChatClient call chain in a
	// generic RuntimeException before it reaches this controller. This proves
	// two things: (1) the ApiException family is still caught even though it
	// is not the directly-thrown type, because @ExceptionHandler matches
	// anywhere in the cause chain (Spring 5.3+); and (2) the surfaced message
	// is Google's actual explanation, not the generic wrapper text - a caller
	// seeing only "Failed to generate content" would have no idea the fix is
	// to change the configured model name.
	@Test
	void returnsServiceUnavailableWithTheRootCauseWhenGoogleRejectsTheModel() throws Exception {
		ClientException modelRetired = new ClientException(404, "NOT_FOUND",
				"This model models/gemini-2.5-flash is no longer available to new users.");
		when(queryService.answer(anyString(), anyString(), anyList()))
				.thenThrow(new RuntimeException("Failed to generate content", modelRetired));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value(
						org.hamcrest.Matchers.containsString("no longer available to new users")))
				.andExpect(jsonPath("$.message").value(
						org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Failed to generate content"))));
	}

	// Regression coverage for BR-08 (specs/chat-provider-switching.md): the
	// same 503 mapping RagExceptionHandler already gives Google's
	// NonTransientAiException/BeanCreationException/ApiException must also
	// cover OpenAI's own exception hierarchy, since a user can now switch
	// the active provider to OpenAI at runtime.
	@Test
	void returnsServiceUnavailableWhenOpenAiRejectsTheRequest() throws Exception {
		com.openai.errors.UnauthorizedException openAiFailure = com.openai.errors.UnauthorizedException.builder()
				.headers(com.openai.core.http.Headers.builder().build())
				.build();
		when(queryService.answer(anyString(), anyString(), anyList())).thenThrow(openAiFailure);

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("401")));
	}

	@Test
	void passesDocumentIdsFromTheRequestBodyToTheService() throws Exception {
		when(queryService.answer(anyString(), anyString(), anyList())).thenReturn(
				RagAnswerResponse.of("q", "answer [1]", RagAnswerResponse.Status.ANSWERED,
						List.of(new SourceReference("doc-1", "a.md", 0, 0.82, true, "text")), 12));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\",\"documentIds\":[\"doc-1\",\"doc-3\"]}"))
				.andExpect(status().isOk());

		verify(queryService).answer("proj-1", "q", List.of("doc-1", "doc-3"));
	}

	@Test
	void omittingDocumentIdsStillSearchesTheWholeProject() throws Exception {
		when(queryService.answer(anyString(), anyString(), anyList())).thenReturn(
				RagAnswerResponse.of("q", "answer [1]", RagAnswerResponse.Status.ANSWERED,
						List.of(new SourceReference("doc-1", "a.md", 0, 0.82, true, "text")), 12));

		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"q\"}"))
				.andExpect(status().isOk());

		verify(queryService).answer("proj-1", "q", List.of());
	}

	@Test
	void returnsBadRequestWhenBodyIsMalformedJson() throws Exception {
		mockMvc.perform(post("/api/projects/proj-1/query")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{ not valid json"))
				.andExpect(status().isBadRequest());
	}
}
