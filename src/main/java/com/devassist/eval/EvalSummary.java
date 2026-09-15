package com.devassist.eval;

public record EvalSummary(
		int totalQuestions,
		int answerableQuestions,
		int unanswerableQuestions,
		Double averageFaithfulness,
		Double averageCompleteness,
		Double correctlyDeclinedRate,
		int failedQuestions,
		int mismatchedOutcomes,
		boolean passed
) {
}
