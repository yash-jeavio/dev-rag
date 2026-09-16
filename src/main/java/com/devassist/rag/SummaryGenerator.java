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
public class SummaryGenerator {

	private static final String SYSTEM_PROMPT = """
			You summarise documents accurately and concisely.
			Use only the supplied text. Do not invent details.
			If the text appears truncated, summarise what is present without speculating about the rest.
			""";

	// Resolved on demand via ChatProviderService rather than a direct
	// ChatModel/ChatClient.Builder dependency: which provider is active can
	// change at runtime (see ChatProviderService), and querying it fresh on
	// every call is also what keeps both providers' underlying beans lazy -
	// a direct dependency on either concrete ChatModel would force Spring to
	// build it at startup.
	private final ChatProviderService chatProviderService;
	private final RagProperties properties;
	private final MeterRegistry meterRegistry;

	public SummaryGenerator(ChatProviderService chatProviderService, RagProperties properties,
			MeterRegistry meterRegistry) {
		this.chatProviderService = chatProviderService;
		this.properties = properties;
		this.meterRegistry = meterRegistry;
	}

	public String summarize(String text, String instruction) {
		String steer = (instruction == null || instruction.isBlank())
				? "Summarise the document."
				: instruction;
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatProviderService.activeChatClientBuilder()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultOptions(ChatOptions.builder().temperature(properties.temperature()))
				.build();

		String provider = chatProviderService.get().name().toLowerCase(Locale.ROOT);
		Timer.Sample sample = Timer.start(meterRegistry);
		ChatResponse response = chatClient.prompt()
				.user("%s\n\nDocument:\n%s".formatted(steer, text))
				.call()
				.chatResponse();
		sample.stop(meterRegistry.timer("rag.summarization.duration", "provider", provider));

		Usage usage = response.getMetadata().getUsage();
		int promptTokens = (usage.getPromptTokens() != null) ? usage.getPromptTokens() : 0;
		int completionTokens = (usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0;
		meterRegistry.counter("rag.summarization.tokens", "provider", provider, "type", "prompt")
				.increment(promptTokens);
		meterRegistry.counter("rag.summarization.tokens", "provider", provider, "type", "completion")
				.increment(completionTokens);

		return response.getResult().getOutput().getText();
	}
}
