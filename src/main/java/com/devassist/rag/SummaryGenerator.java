package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class SummaryGenerator {

	private static final String SYSTEM_PROMPT = """
			You summarise documents accurately and concisely.
			Use only the supplied text. Do not invent details.
			If the text appears truncated, summarise what is present without speculating about the rest.
			""";

	private final ChatClient chatClient;

	public SummaryGenerator(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
	}

	public String summarize(String text, String instruction) {
		String steer = (instruction == null || instruction.isBlank())
				? "Summarise the document."
				: instruction;
		return chatClient.prompt()
				.user("%s\n\nDocument:\n%s".formatted(steer, text))
				.call()
				.content();
	}
}
