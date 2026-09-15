package com.devassist.eval;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.devassist.rag.ChatProviderService;
import com.devassist.rag.EvaluationScore;
import com.devassist.rag.SourceReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeServiceTest {

	private JudgeService serviceReturning(String rawModelOutput) {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		when(chatModel.call(org.mockito.ArgumentMatchers.any(org.springframework.ai.chat.prompt.Prompt.class)))
				.thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(rawModelOutput)))));

		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));

		return new JudgeService(chatProviderService, new ObjectMapper());
	}

	private final List<SourceReference> sources = List.of(
			new SourceReference("doc-1", "product-policy.txt", 0, 0.81, true, "30-day return window"));

	@Test
	void parsesCleanJson() {
		JudgeService service = serviceReturning(
				"{\"faithfulness\": 5, \"completeness\": 4, \"reasoning\": \"Matches the source exactly.\"}");

		EvaluationScore score = service.judgeAnswered("q", "30 days. [1]", sources);

		assertThat(score.faithfulness()).isEqualTo(5);
		assertThat(score.completeness()).isEqualTo(4);
		assertThat(score.reasoning()).isEqualTo("Matches the source exactly.");
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.JUDGED);
	}

	@Test
	void parsesJsonWrappedInMarkdownFences() {
		JudgeService service = serviceReturning(
				"```json\n{\"faithfulness\": 3, \"completeness\": 3, \"reasoning\": \"Partial.\"}\n```");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.faithfulness()).isEqualTo(3);
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.JUDGED);
	}

	@Test
	void treatsGarbageOutputAsUnscorableRatherThanThrowing() {
		JudgeService service = serviceReturning("I refuse to output JSON today.");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.method()).isEqualTo(EvaluationScore.Method.UNSCORABLE);
		assertThat(score.faithfulness()).isNull();
		assertThat(score.reasoning()).isNotBlank();
	}

	@Test
	void treatsJsonMissingRequiredFieldsAsUnscorable() {
		JudgeService service = serviceReturning("{\"reasoning\": \"I forgot the scores.\"}");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.method()).isEqualTo(EvaluationScore.Method.UNSCORABLE);
	}

	@Test
	void treatsAnOutOfRangeScoreAsUnscorable() {
		JudgeService service = serviceReturning(
				"{\"faithfulness\": 10, \"completeness\": 3, \"reasoning\": \"Hallucinated scale.\"}");

		EvaluationScore score = service.judgeAnswered("q", "answer", sources);

		assertThat(score.method()).isEqualTo(EvaluationScore.Method.UNSCORABLE);
	}

	@Test
	void sendsTheQuestionAnswerAndSourceExcerptsInThePrompt() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
		org.mockito.ArgumentCaptor<org.springframework.ai.chat.prompt.Prompt> promptCaptor =
				org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
		when(chatModel.call(promptCaptor.capture())).thenReturn(
				new ChatResponse(List.of(new Generation(new AssistantMessage(
						"{\"faithfulness\": 5, \"completeness\": 5, \"reasoning\": \"ok\"}")))));
		ChatProviderService chatProviderService = mock(ChatProviderService.class);
		when(chatProviderService.activeChatClientBuilder()).thenReturn(ChatClient.builder(chatModel));
		JudgeService service = new JudgeService(chatProviderService, new ObjectMapper());

		service.judgeAnswered("What is the return window?", "30 days. [1]", sources);

		String promptText = promptCaptor.getValue().getContents();
		assertThat(promptText).contains("What is the return window?");
		assertThat(promptText).contains("30 days. [1]");
		assertThat(promptText).contains("30-day return window");
	}
}
