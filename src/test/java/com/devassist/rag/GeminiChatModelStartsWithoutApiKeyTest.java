package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression test for the task-13 finding: the application refused to start
 * with the real production chat provider (google-genai) configured whenever
 * GEMINI_API_KEY was unset, even though ingestion, listing, and index-status
 * never touch Gemini, and BR-08 guarantees the chat model is never called
 * when retrieval finds nothing.
 *
 * The "test" profile normally reroutes chat to Ollama (see
 * application-test.properties) specifically to dodge this failure, which is
 * exactly why the other 151 tests never caught it. This test deliberately
 * restores the real spring.ai.model.chat=google-genai setting and blanks the
 * api-key, so it fails loudly if GeminiLazyChatModelConfiguration (or the
 * ObjectProvider indirection in GenerationService/SummaryGenerator) ever
 * regresses and the Gemini client bean becomes eagerly constructed again.
 */
@SpringBootTest
@TestPropertySource(properties = { "spring.ai.model.chat=google-genai", "spring.ai.google.genai.api-key=" })
class GeminiChatModelStartsWithoutApiKeyTest {

	@Test
	void contextLoadsWithGoogleGenAiConfiguredAndNoApiKey() {
		// Intentionally empty: reaching this point means the application
		// context started successfully with the real Gemini provider wired in
		// but no API key present - the regression this test guards against.
	}
}
