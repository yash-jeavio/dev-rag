package com.devassist.rag;

public record SourceReference(
		String documentId,
		String title,
		int chunkIndex,
		double similarity,
		boolean cited,
		String excerpt
) {
}
