package com.devassist.rag;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Implements BR-06 and BR-07: retrieval is always scoped to the requested
 * project and bounded to the configured top-k and similarity threshold.
 */
@Service
public class RetrievalService {

	private final VectorStore vectorStore;
	private final RagProperties properties;

	public RetrievalService(VectorStore vectorStore, RagProperties properties) {
		this.vectorStore = vectorStore;
		this.properties = properties;
	}

	public List<Document> retrieve(String projectId, String question) {
		// BR-06: built with FilterExpressionBuilder rather than string
		// concatenation, so a projectId can never be interpreted as filter syntax.
		SearchRequest request = SearchRequest.builder()
				.query(question)
				.topK(properties.topK())
				.similarityThreshold(properties.similarityThreshold())
				.filterExpression(new FilterExpressionBuilder().eq("projectId", projectId).build())
				.build();

		List<Document> results = vectorStore.similaritySearch(request);
		return (results != null) ? results : List.of();
	}
}
