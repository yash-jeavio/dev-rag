package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
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

	public SummaryGenerator(ChatProviderService chatProviderService, RagProperties properties) {
		this.chatProviderService = chatProviderService;
		this.properties = properties;
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
		return chatClient.prompt()
				.user("%s\n\nDocument:\n%s".formatted(steer, text))
				.call()
				.content();
	}
}
