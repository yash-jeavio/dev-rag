package com.devassist.rag;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Implements BR-06 and BR-07 (specs/rag-core.md): retrieval is always
 * scoped to the requested project and bounded to the configured top-k and
 * similarity threshold. Also implements BR-01/BR-02
 * (specs/document-scoped-retrieval.md): when documentIds is non-empty,
 * retrieval additionally narrows to just those documents - within the
 * project, never across it, since the projectId clause is always ANDed in.
 */
@Service
public class RetrievalService {

	private final VectorStore vectorStore;
	private final RagProperties properties;

	public RetrievalService(VectorStore vectorStore, RagProperties properties) {
		this.vectorStore = vectorStore;
		this.properties = properties;
	}

	public List<Document> retrieve(String projectId, String question, List<String> documentIds) {
		SearchRequest request = SearchRequest.builder()
				.query(question)
				.topK(properties.topK())
				.similarityThreshold(properties.similarityThreshold())
				.filterExpression(buildFilter(projectId, documentIds))
				.build();

		List<Document> results = vectorStore.similaritySearch(request);
		return (results != null) ? results : List.of();
	}

	// BR-06: built with FilterExpressionBuilder rather than string
	// concatenation, so a projectId or documentId value can never be
	// interpreted as filter syntax.
	private Filter.Expression buildFilter(String projectId, List<String> documentIds) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		if (documentIds.isEmpty()) {
			return builder.eq("projectId", projectId).build();
		}
		// FilterExpressionBuilder.in(String, List<Object>) does not accept
		// List<String> directly (Java generics are invariant) - the varargs
		// overload in(String, Object...) does, and List.toArray() already
		// returns Object[].
		return builder.and(builder.eq("projectId", projectId), builder.in("documentId", documentIds.toArray()))
				.build();
	}
}
