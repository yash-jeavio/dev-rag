package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatProviderServiceTest {

	@Test
	void defaultsToGemini() {
		ChatProviderService service = new ChatProviderService(mockProvider(mock(GoogleGenAiChatModel.class)),
				mockProvider(mock(OpenAiChatModel.class)));

		assertThat(service.get()).isEqualTo(ChatProvider.GEMINI);
	}

	@Test
	void setChangesTheActiveProvider() {
		ChatProviderService service = new ChatProviderService(mockProvider(mock(GoogleGenAiChatModel.class)),
				mockProvider(mock(OpenAiChatModel.class)));

		service.set(ChatProvider.OPENAI);

		assertThat(service.get()).isEqualTo(ChatProvider.OPENAI);
	}

	@Test
	void activeChatClientBuilderResolvesOnlyGeminiWhenGeminiIsActive() {
		ObjectProvider<GoogleGenAiChatModel> geminiProvider = mockProvider(mock(GoogleGenAiChatModel.class));
		ObjectProvider<OpenAiChatModel> openAiProvider = mockProvider(mock(OpenAiChatModel.class));
		ChatProviderService service = new ChatProviderService(geminiProvider, openAiProvider);

		ChatClient.Builder builder = service.activeChatClientBuilder();

		assertThat(builder).isNotNull();
		verify(geminiProvider).getObject();
		verify(openAiProvider, never()).getObject();
	}

	@Test
	void activeChatClientBuilderResolvesOnlyOpenAiWhenOpenAiIsActive() {
		ObjectProvider<GoogleGenAiChatModel> geminiProvider = mockProvider(mock(GoogleGenAiChatModel.class));
		ObjectProvider<OpenAiChatModel> openAiProvider = mockProvider(mock(OpenAiChatModel.class));
		ChatProviderService service = new ChatProviderService(geminiProvider, openAiProvider);
		service.set(ChatProvider.OPENAI);

		ChatClient.Builder builder = service.activeChatClientBuilder();

		assertThat(builder).isNotNull();
		verify(openAiProvider).getObject();
		verify(geminiProvider, never()).getObject();
	}

	@SuppressWarnings("unchecked")
	private <T> ObjectProvider<T> mockProvider(T instance) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		when(provider.getObject()).thenReturn(instance);
		return provider;
	}
}
