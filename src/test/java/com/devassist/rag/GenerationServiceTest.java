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
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, new SimpleMeterRegistry());
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
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.42, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, new SimpleMeterRegistry());
		service.generate("context", "question");

		assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.42);
	}

	@Test
	void recordsGenerationDurationAndTokenUsageTaggedByProvider() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
				List.of(new Generation(new AssistantMessage("answer"))),
				ChatResponseMetadata.builder().usage(new DefaultUsage(42, 17)).build()));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, meterRegistry);

		service.generate("context", "question");

		Timer timer = meterRegistry.find("rag.generation.duration").tag("provider", "gemini").timer();
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);

		Counter promptTokens = meterRegistry.find("rag.generation.tokens")
				.tag("provider", "gemini")
				.tag("type", "prompt")
				.counter();
		assertThat(promptTokens).isNotNull();
		assertThat(promptTokens.count()).isEqualTo(42);

		Counter completionTokens = meterRegistry.find("rag.generation.tokens")
				.tag("provider", "gemini")
				.tag("type", "completion")
				.counter();
		assertThat(completionTokens).isNotNull();
		assertThat(completionTokens.count()).isEqualTo(17);
	}

	@Test
	void recordsNoMetricWhenTheModelCallFails() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("provider unavailable"));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, meterRegistry);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.generate("context", "question"))
				.isInstanceOf(RuntimeException.class);

		assertThat(meterRegistry.find("rag.generation.duration").timer()).isNull();
		assertThat(meterRegistry.find("rag.generation.tokens").counter()).isNull();
	}

	@Test
	void treatsNullTokenCountsAsZeroInsteadOfThrowing() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());

		org.springframework.ai.chat.metadata.Usage usageWithNullCounts = mock(
				org.springframework.ai.chat.metadata.Usage.class);
		when(usageWithNullCounts.getPromptTokens()).thenReturn(null);
		when(usageWithNullCounts.getCompletionTokens()).thenReturn(null);
		when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(
				List.of(new Generation(new AssistantMessage("answer"))),
				ChatResponseMetadata.builder().usage(usageWithNullCounts).build()));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		when(chatProviderService.get()).thenReturn(ChatProvider.GEMINI);

		MeterRegistry meterRegistry = new SimpleMeterRegistry();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		GenerationService service = new GenerationService(chatProviderService, properties, meterRegistry);

		String answer = service.generate("context", "question");

		assertThat(answer).isEqualTo("answer");
		Counter promptTokens = meterRegistry.find("rag.generation.tokens").tag("type", "prompt").counter();
		assertThat(promptTokens.count()).isEqualTo(0);
		Counter completionTokens = meterRegistry.find("rag.generation.tokens").tag("type", "completion").counter();
		assertThat(completionTokens.count()).isEqualTo(0);
	}
}
