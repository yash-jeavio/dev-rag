package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiChatConfigurationTest {

	private final OpenAiChatConfiguration configuration = new OpenAiChatConfiguration();

	@Test
	void buildsAChatModelWhenAnApiKeyIsPresent() {
		OpenAiCommonProperties commonProperties = new OpenAiCommonProperties();
		commonProperties.setApiKey("test-key");
		OpenAiChatProperties chatProperties = new OpenAiChatProperties();
		chatProperties.setModel("gpt-4o-mini");

		OpenAiChatModel chatModel = configuration.openAiChatModel(commonProperties, chatProperties);

		assertThat(chatModel).isNotNull();
	}

	// Unlike Gemini, OpenAI's client does not fail fast on a missing key -
	// it silently builds a "no-auth mode" client (specs/chat-provider-switching.md
	// S4). Building the client is local configuration only, no network call
	// happens until a real chat request is made, so this must not throw.
	@Test
	void buildsAChatModelEvenWithNoApiKey() {
		OpenAiCommonProperties commonProperties = new OpenAiCommonProperties();
		OpenAiChatProperties chatProperties = new OpenAiChatProperties();
		chatProperties.setModel("gpt-4o-mini");

		OpenAiChatModel chatModel = configuration.openAiChatModel(commonProperties, chatProperties);

		assertThat(chatModel).isNotNull();
	}
}
