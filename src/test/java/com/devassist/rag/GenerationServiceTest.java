package com.devassist.rag;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationServiceTest {

	@Test
	void sendsAFixedSystemPromptAndReturnsTheModelsAnswer() {
		ChatModel chatModel = mock(ChatModel.class);
		// The real ChatClient merges request options with the model's defaults,
		// so a mock must return real ChatOptions rather than Mockito's null default.
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("Within 30 days. [1]")))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties);
		String answer = service.generate("[1] refund text", "refund window?");

		assertThat(answer).isEqualTo("Within 30 days. [1]");

		// BR-09: the refusal wording must be the fixed, exact phrase a later
		// evaluator matches on, not paraphrased or reworded in the prompt.
		String promptText = promptCaptor.getValue().getContents();
		assertThat(promptText).contains("I don't have enough information to answer this.");
		assertThat(promptText).contains("ONLY the provided context");
		assertThat(promptText).contains("[1] refund text");
		assertThat(promptText).contains("refund window?");
	}

	// Guards against the two literals (here and in RagQueryService) drifting
	// apart again now that a shared constant exists.
	@Test
	void systemPromptEmbedsTheSharedRefusalConstant() {
		assertThat(GenerationService.SYSTEM_PROMPT).contains(RagQueryService.NO_CONTEXT_ANSWER);
	}

	// Fix 1: devassist.rag.temperature was configured and bound but never
	// actually passed to the ChatClient, so generation silently ran at the
	// provider's default (~1.0) instead of the low, factual-grounding value
	// BR-09 calls for. This asserts the configured value reaches the actual
	// Prompt sent to the model, not just that the property binds.
	@Test
	void appliesTheConfiguredTemperatureToTheChatClient() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.42, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties);
		service.generate("context", "question");

		assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.42);
	}
}
