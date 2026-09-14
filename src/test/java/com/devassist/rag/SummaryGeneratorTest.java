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
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SummaryGeneratorTest {

	@Test
	void sendsTheDocumentAndInstructionAndReturnsTheModelsSummary() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("A short summary.")))));

		@SuppressWarnings("unchecked")
		ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
		when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(builderProvider, properties);
		String summary = generator.summarize("document body", "Focus on timeframes.");

		assertThat(summary).isEqualTo("A short summary.");
		String promptText = promptCaptor.getValue().getContents();
		assertThat(promptText).contains("document body");
		assertThat(promptText).contains("Focus on timeframes.");
	}

	// Fix 1: devassist.rag.temperature was configured and bound but never
	// actually passed to the ChatClient here either, so summarisation also ran
	// at the provider's default (~1.0) instead of BR-09's low, factual value.
	@Test
	void appliesTheConfiguredTemperatureToTheChatClient() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
		when(chatModel.call(promptCaptor.capture()))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("summary")))));

		@SuppressWarnings("unchecked")
		ObjectProvider<ChatClient.Builder> builderProvider = mock(ObjectProvider.class);
		when(builderProvider.getObject()).thenReturn(ChatClient.builder(chatModel));

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.33, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(builderProvider, properties);
		generator.summarize("document body", null);

		assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.33);
	}
}
