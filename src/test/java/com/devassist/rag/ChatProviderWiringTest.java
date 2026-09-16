package com.devassist.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: with two ChatModel beans in the context (Gemini and
 * OpenAI), any component that still depended on Spring AI's own
 * autoconfigured ChatClient.Builder (which requires exactly one ChatModel
 * candidate) would fail to wire. This proved SummaryGenerator was missed
 * by the original provider-switching migration - it's the exact regression
 * this test guards against for every future chat-related component.
 */
@SpringBootTest
class ChatProviderWiringTest {

	@Autowired
	private ChatProviderService chatProviderService;

	@Autowired
	private SummaryGenerator summaryGenerator;

	@Autowired
	private GenerationService generationService;

	@Test
	void allChatConsumersWireCleanlyWithBothProvidersPresent() {
		assertThat(chatProviderService).isNotNull();
		assertThat(summaryGenerator).isNotNull();
		assertThat(generationService).isNotNull();
	}
}
