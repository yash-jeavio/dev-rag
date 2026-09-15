package com.devassist.eval;

import com.devassist.rag.RagAnswerResponse;

public record EvalResultEntry(
		String question,
		boolean expectedAnswerable,
		RagAnswerResponse response,
		boolean outcomeMatchedExpectation,
		String failureReason
) {
}
