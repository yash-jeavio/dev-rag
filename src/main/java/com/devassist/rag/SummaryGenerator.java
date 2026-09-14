package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class SummaryGenerator {

	private static final String SYSTEM_PROMPT = """
			You summarise documents accurately and concisely.
			Use only the supplied text. Do not invent details.
			If the text appears truncated, summarise what is present without speculating about the rest.
			""";

	// See GenerationService for why this is an ObjectProvider rather than a
	// plain ChatClient.Builder: it defers building the Gemini ChatModel/Client
	// (and validating GEMINI_API_KEY) to the first real summarize() call
	// instead of forcing it at application startup.
	private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
	private final RagProperties properties;

	public SummaryGenerator(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider, RagProperties properties) {
		this.chatClientBuilderProvider = chatClientBuilderProvider;
		this.properties = properties;
	}

	public String summarize(String text, String instruction) {
		String steer = (instruction == null || instruction.isBlank())
				? "Summarise the document."
				: instruction;
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatClientBuilderProvider.getObject()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultOptions(ChatOptions.builder().temperature(properties.temperature()))
				.build();
		return chatClient.prompt()
				.user("%s\n\nDocument:\n%s".formatted(steer, text))
				.call()
				.content();
	}
}
