package com.devassist.rag;

import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Spring AI's own OpenAiChatAutoConfiguration is gated by the same
// spring.ai.model.chat discriminator property Gemini's autoconfiguration
// uses (@ConditionalOnProperty(..., havingValue = "openai")) - with that
// property set to "google-genai" for Gemini, OpenAI's autoconfiguration
// never activates and its ChatModel bean is never created, regardless of
// which starters are on the classpath. This class hand-builds the bean
// instead, the same way RagConfiguration hand-builds the VectorStore bean,
// so both providers can coexist without touching Gemini's already-reviewed
// autoconfiguration at all.
@Configuration
@EnableConfigurationProperties({ OpenAiCommonProperties.class, OpenAiChatProperties.class })
public class OpenAiChatConfiguration {

	@Bean
	public OpenAiChatModel openAiChatModel(OpenAiCommonProperties commonProperties,
			OpenAiChatProperties chatProperties) {
		OpenAiAutoConfigurationUtil.ResolvedConnectionProperties resolved = OpenAiAutoConfigurationUtil
				.resolveCommonProperties(commonProperties, chatProperties);

		// Unlike Gemini, an empty/missing API key here does not throw - the
		// OpenAI SDK silently builds a client in "no-auth mode" and only
		// fails once a real request goes out (specs/chat-provider-switching.md
		// S4). OpenAiChatModel.build() constructs the underlying
		// OpenAIClient/OpenAIClientAsync itself from these options when
		// neither is supplied explicitly, so no manual client wiring is
		// needed here.
		// null only arises when this bean is constructed directly in a unit
		// test bypassing Spring's property binding (AbstractOpenAiProperties'
		// apiKey field has no initializer) - the real app always binds an
		// empty string via spring.ai.openai.api-key=${OPENAI_API_KEY:}. Both
		// must become "" here: a null API key makes the OpenAI SDK fall
		// through to its own env-var-reading fallback instead of its
		// deterministic no-auth-mode path.
		String apiKey = resolved.getApiKey() == null ? "" : resolved.getApiKey();

		OpenAiChatOptions options = OpenAiChatOptions.builder()
				.apiKey(apiKey)
				.model(resolved.getModel())
				.build();

		return OpenAiChatModel.builder().options(options).build();
	}
}
