package com.devassist.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
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

	// Resolved on demand rather than injected as a plain ChatClient.Builder: a
	// direct constructor dependency would force Spring to build the Gemini
	// ChatModel/Client (and validate GEMINI_API_KEY) as soon as this
	// (non-lazy) service is created, i.e. at application startup, even for
	// requests that never call the model at all (BR-08). ObjectProvider defers
	// that resolution to the moment a chat call is actually made. See
	// GeminiLazyChatModelConfiguration for the other half of this fix.
	private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
	private final RagProperties properties;

	public GenerationService(ObjectProvider<ChatClient.Builder> chatClientBuilderProvider, RagProperties properties) {
		this.chatClientBuilderProvider = chatClientBuilderProvider;
		this.properties = properties;
	}

	public String generate(String context, String question) {
		// BR-09: low temperature for factual grounding rather than the
		// provider's default (~1.0).
		ChatClient chatClient = chatClientBuilderProvider.getObject()
				.defaultSystem(SYSTEM_PROMPT)
				.defaultOptions(ChatOptions.builder().temperature(properties.temperature()))
				.build();
		return chatClient.prompt()
				.user("Context:\n%s\n\nQuestion: %s".formatted(context, question))
				.call()
				.content();
	}
}
