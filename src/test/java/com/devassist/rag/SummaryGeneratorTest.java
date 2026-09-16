package com.devassist.rag;

import java.util.List;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(chatProviderService, properties, new SimpleMeterRegistry());
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

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.33, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(chatProviderService, properties, new SimpleMeterRegistry());
		generator.summarize("document body", null);

		assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.33);
	}

	@Test
	void recordsSummarizationDurationAndTokenUsageTaggedByProvider() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
				List.of(new Generation(new AssistantMessage("summary"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(30, 10)).build()));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.OPENAI);

		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		SummaryGenerator generator = new SummaryGenerator(chatProviderService, properties, meterRegistry);

		generator.summarize("document body", null);

		Timer timer = meterRegistry.find("rag.summarization.duration").tag("provider", "openai").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);

		Counter promptTokens = meterRegistry.find("rag.summarization.tokens")
				.tag("provider", "openai")
				.tag("type", "prompt")
				.counter();
		assertThat(promptTokens).isNotNull();
		assertThat(promptTokens.count()).isEqualTo(30);

		Counter completionTokens = meterRegistry.find("rag.summarization.tokens")
				.tag("provider", "openai")
				.tag("type", "completion")
				.counter();
		assertThat(completionTokens).isNotNull();
		assertThat(completionTokens.count()).isEqualTo(10);
	}
}
