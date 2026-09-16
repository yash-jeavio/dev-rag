package com.devassist.rag;

import java.util.Locale;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

@Service
public class GenerationService {

	// BR-09: the "I don't have enough information" wording is fixed so that
	// sub-project 3 can detect refusals without parsing prose. Interpolated
	// from RagQueryService.NO_CONTEXT_ANSWER (the single definition) instead
	// of being retyped here, so the two call sites cannot drift apart.
	static final String SYSTEM_PROMPT = """
			You are a careful assistant answering questions about a user's documents.

			Rules:
			1. Answer using ONLY the provided context. Never use outside knowledge.
			2. If the context does not contain enough information, reply with exactly:
			   %s
			3. Cite the context blocks you used with markers like [1] or [2].
			""".formatted(RagQueryService.NO_CONTEXT_ANSWER);

	// Resolved on demand via ChatProviderService rather than a direct
	// ChatModel/ChatClient.Builder dependency: which provider is active can
	// change at runtime (see ChatProviderService), and querying it fresh on
	// every call is also what keeps both providers' underlying beans lazy
	// (BR-01, specs/chat-provider-switching.md) - a direct dependency on
	// either concrete ChatModel would force Spring to build it at startup.
	private final ChatProviderService chatProviderService;
	private final RagProperties properties;
	private final MeterRegistry meterRegistry;

	public GenerationService(ChatProviderService chatProviderService, RagProperties properties,
			MeterRegistry meterRegistry) {
		this.chatProviderService = chatProviderService;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	public String generate(String context, String question) {
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatProviderService.activeChatClientBuilder()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultOptions(ChatOptions.builder().temperature(properties.temperature()))
				.build();

		String provider = chatProviderService.get().name().toLowerCase(Locale.ROOT);
		Timer.Sample sample = Timer.start(meterRegistry);
		ChatResponse response = chatClient.prompt()
				.user("Context:\n%s\n\nQuestion: %s".formatted(context, question))
				.call()
				.chatResponse();
		sample.stop(meterRegistry.timer("rag.generation.duration", "provider", provider));

		Usage usage = response.getMetadata().getUsage();
		int promptTokens = (usage.getPromptTokens() != null) ? usage.getPromptTokens() : 0;
		int completionTokens = (usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
		meterRegistry.counter("rag.generation.tokens", "provider", provider, "type", "prompt").increment(promptTokens);
		meterRegistry.counter("rag.generation.tokens", "provider", provider, "type", "completion")
				.increment(completionTokens);

		return response.getResult().getOutput().getText();
	}
}
