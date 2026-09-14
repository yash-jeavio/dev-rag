package com.devassist.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("devassist.rag")
public record RagProperties(
		int topK,
		double similarityThreshold,
		int chunkSizeChars,
		int chunkOverlapChars,
		double temperature,
		int maxExcerptChars,
		int summaryMaxChars
) {
}
