package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatProviderController.class)
class ChatProviderControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ChatProviderService chatProviderService;

	@Test
	void getReturnsTheCurrentProvider() throws Exception {
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		mockMvc.perform(get("/api/settings/chat-provider"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("GEMINI"));
	}

	@Test
	void putUpdatesTheActiveProvider() throws Exception {
		when(chatProviderService.get()).thenReturn(ChatProvider.OPENAI);

		mockMvc.perform(put("/api/settings/chat-provider").contentType(MediaType.APPLICATION_JSON)
				.content("{\"provider\":\"OPENAI\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.provider").value("OPENAI"));

		verify(chatProviderService).set(ChatProvider.OPENAI);
	}

	// BR-07: an invalid enum value fails Jackson deserialization before the
	// controller method runs at all, so Spring Boot's own default error
	// handling returns 400 - no custom exception handler is added for this
	// endpoint.
	@Test
	void putWithAnInvalidProviderReturnsBadRequest() throws Exception {
		mockMvc.perform(put("/api/settings/chat-provider").contentType(MediaType.APPLICATION_JSON)
				.content("{\"provider\":\"NOT_A_REAL_PROVIDER\"}"))
				.andExpect(status().isBadRequest());
	}
}
