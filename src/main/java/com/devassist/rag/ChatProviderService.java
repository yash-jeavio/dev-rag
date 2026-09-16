package com.devassist.rag;

import java.util.Objects;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

// The single place that knows both chat providers exist. GenerationService
// and JudgeService ask this for "the active" ChatClient.Builder instead of
// resolving a provider-specific bean themselves - adding, removing, or
// replacing a provider later is a change confined to this class and one
// configuration class, never to either consumer (BR-06,
// specs/chat-provider-switching.md). Building ChatClient.builder(chatModel)
// directly here bypasses Spring AI's own ChatClientBuilderConfigurer
// (which would apply any registered ChatClientCustomizer/
// ChatClientBuilderCustomizer beans, observation registry, or tool-calling
// advisor) - harmless today since this app registers none of those, but
// worth naming as a deliberate simplification rather than an oversight.
@Service
public class ChatProviderService {

	// Resolving via ObjectProvider inside activeChatClientBuilder(), not in
	// the constructor, is what keeps both providers' ChatModel beans lazy
	// (BR-01) - this method only runs from an actual query/judge call,
	// never at startup or at ChatProviderService construction time.
	private final ObjectProvider<GoogleGenAiChatModel> googleGenAiChatModelProvider;
	private final ObjectProvider<OpenAiChatModel> openAiChatModelProvider;

	// volatile: this field is read and written from whichever HTTP request
	// thread happens to be handling a query, a judge call, or a settings
	// change - visibility across threads matters even though updates are
	// infrequent.
	private volatile ChatProvider currentProvider = ChatProvider.GEMINI;

	public ChatProviderService(ObjectProvider<GoogleGenAiChatModel> googleGenAiChatModelProvider,
			ObjectProvider<OpenAiChatModel> openAiChatModelProvider) {
		this.googleGenAiChatModelProvider = googleGenAiChatModelProvider;
		this.openAiChatModelProvider = openAiChatModelProvider;
	}

	public ChatProvider get() {
		return currentProvider;
	}

	public void set(ChatProvider provider) {
		Objects.requireNonNull(provider, "provider must not be null");
		this.currentProvider = provider;
	}

	public ChatClient.Builder activeChatClientBuilder() {
		return switch (currentProvider) {
			case GEMINI -> ChatClient.builder(googleGenAiChatModelProvider.getObject());
			case OPENAI -> ChatClient.builder(openAiChatModelProvider.getObject());
		};
	}
}
