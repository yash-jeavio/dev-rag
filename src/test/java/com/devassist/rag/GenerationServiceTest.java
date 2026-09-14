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

		GenerationService service = new GenerationService(ChatClient.builder(chatModel));
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
}
