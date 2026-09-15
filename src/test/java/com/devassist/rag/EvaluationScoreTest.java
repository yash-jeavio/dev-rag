package com.devassist.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScoreTest {

	@Test
	void carriesJudgedScoresWithNoCorrectlyDeclinedValue() {
		EvaluationScore score = new EvaluationScore(5, 4, null, "Faithful and complete.",
				EvaluationScore.Method.JUDGED);

		assertThat(score.faithfulness()).isEqualTo(5);
		assertThat(score.completeness()).isEqualTo(4);
		assertThat(score.correctlyDeclined()).isNull();
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.JUDGED);
	}

	@Test
	void carriesProgrammaticCorrectlyDeclinedWithNoJudgeScores() {
		EvaluationScore score = new EvaluationScore(null, null, true,
				"Question was marked unanswerable and the system correctly declined.",
				EvaluationScore.Method.PROGRAMMATIC);

		assertThat(score.faithfulness()).isNull();
		assertThat(score.completeness()).isNull();
		assertThat(score.correctlyDeclined()).isTrue();
		assertThat(score.method()).isEqualTo(EvaluationScore.Method.PROGRAMMATIC);
	}
}
