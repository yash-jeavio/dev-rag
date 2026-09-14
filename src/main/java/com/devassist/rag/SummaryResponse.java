package com.devassist.rag;

public record SummaryResponse(
		String documentId,
		String title,
		String summary,
		int charactersUsed,
		boolean truncated,
		long latencyMs
) {
}
