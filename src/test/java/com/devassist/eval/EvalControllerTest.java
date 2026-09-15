package com.devassist.eval;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.devassist.rag.EvaluationScore;
import com.devassist.rag.RagAnswerResponse;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvalController.class)
class EvalControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EvaluationService evaluationService;

	@Test
	void returnsTheReportOnSuccess() throws Exception {
		RagAnswerResponse response = new RagAnswerResponse("q1", "30 days. [1]", RagAnswerResponse.Status.ANSWERED,
				List.of(), 100, new EvaluationScore(5, 5, null, "ok", EvaluationScore.Method.JUDGED));
		EvalResultEntry entry = new EvalResultEntry("q1", true, response, true, null);
		EvalSummary summary = new EvalSummary(1, 1, 0, 5.0, 5.0, null, 0, 0, true);
		when(evaluationService.runEvaluation()).thenReturn(new EvalReportResponse(List.of(entry), summary));

		mockMvc.perform(post("/api/eval/run"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.passed").value(true))
				.andExpect(jsonPath("$.results[0].question").value("q1"))
				.andExpect(jsonPath("$.results[0].response.evaluation.faithfulness").value(5));
	}

	@Test
	void returnsServiceUnavailableWhenCorpusIsNotReady() throws Exception {
		when(evaluationService.runEvaluation()).thenThrow(new EvalCorpusNotReadyException());

		mockMvc.perform(post("/api/eval/run"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503));
	}
}
