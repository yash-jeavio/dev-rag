package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
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

	public SummaryGenerator(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider) {
		this.chatClientBuilderProvider = chatClientBuilderProvider;
	}

	public String summarize(String text, String instruction) {
		String steer = (instruction == null || instruction.isBlank())
				? "Summarise the document."
				: instruction;
		ChatClient chatClient = chatClientBuilderProvider.getObject().defaultSystem(SYSTEM_PROMPT).build();
		return chatClient.prompt()
				.user("%s\n\nDocument:\n%s".formatted(steer, text))
				.call()
				.content();
	}
}
