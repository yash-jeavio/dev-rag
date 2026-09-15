package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression test proving OpenAiChatConfiguration's bean stays lazy: the
 * full application context must start successfully with no usable OpenAI
 * key, the same guarantee GeminiChatModelStartsWithoutApiKeyTest already
 * proves for Gemini. Explicitly blanks the key via @TestPropertySource
 * rather than relying on OPENAI_API_KEY being unset in whatever
 * environment runs this test - a developer's own shell may have it
 * exported for manual testing, which would silently defeat the point of
 * this test if it only checked the ambient environment. Unlike Gemini's
 * version of this test, no spring.ai.model.chat override is needed here:
 * OpenAiChatConfiguration's bean is unconditional, so it is present in
 * every profile already, including the default one this test runs under.
 */
@SpringBootTest
@TestPropertySource(properties = "spring.ai.openai.api-key=")
class OpenAiChatModelStartsWithoutApiKeyTest {

	@Test
	void contextLoadsWithNoOpenAiApiKeySet() {
		// Intentionally empty: reaching this point means the application
		// context started successfully with the OpenAiChatModel bean
		// registered but no usable API key present - the regression this
		// test guards against.
	}
}
