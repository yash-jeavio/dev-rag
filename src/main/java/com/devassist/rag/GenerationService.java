package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class GenerationService {

	// BR-09: the "I don't have enough information" wording is fixed so that
	// sub-project 3 can detect refusals without parsing prose.
	private static final String SYSTEM_PROMPT = """
			You are a careful assistant answering questions about a user's documents.

			Rules:
			1. Answer using ONLY the provided context. Never use outside knowledge.
			2. If the context does not contain enough information, reply with exactly:
			   I don't have enough information to answer this.
			3. Cite the context blocks you used with markers like [1] or [2].
			""";

	private final ChatClient chatClient;

	public GenerationService(ChatClient.Builder chatClientBuilder) {
		this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
	}

	public String generate(String context, String question) {
		return chatClient.prompt()
				.user("Context:\n%s\n\nQuestion: %s".formatted(context, question))
				.call()
				.content();
	}
}
