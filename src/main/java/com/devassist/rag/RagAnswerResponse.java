package com.devassist.rag;

import java.util.List;

public record RagAnswerResponse(
		String question,
		String answer,
		Status status,
		List<SourceReference> sources,
		long latencyMs,
		Object evaluation
) {

	public enum Status {
		ANSWERED,
		INSUFFICIENT_CONTEXT
	}

	// Reserved for sub-project 3; always null here so the contract does not
	// change when evaluation scores are added.
	static RagAnswerResponse of(String question, String answer, Status status, List<SourceReference> sources,
			long latencyMs) {
		return new RagAnswerResponse(question, answer, status, sources, latencyMs, null);
	}
}
