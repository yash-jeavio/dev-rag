package com.devassist.rag;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

	private VectorStore vectorStore;
	private RetrievalService service;

	@BeforeEach
	void setUp() {
		vectorStore = mock(VectorStore.class);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
		service = new RetrievalService(vectorStore, new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000));
	}

	@Test
	void alwaysFiltersByProjectId() {
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getFilterExpression()).isNotNull();
		assertThat(captor.getValue().getFilterExpression().toString()).contains("proj-1");
	}

	@Test
	void appliesConfiguredTopKAndThreshold() {
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getTopK()).isEqualTo(5);
		assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.5);
	}

	@Test
	void returnsEmptyListWhenStoreReturnsNull() {
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

		assertThat(service.retrieve("proj-1", "q", List.of())).isEmpty();
	}

	@Test
	void filterIsScopedToTheProjectIdMetadataKeyNotSomeOtherKey() {
		// Guards against a filter built on the wrong metadata key (e.g.
		// "documentId") that would still happen to contain the projectId value.
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getFilterExpression().toString()).contains("projectId");
	}

	@Test
	void topKAndThresholdAreNotHardcodedButReadFromProperties() {
		// Different configured values than the setUp() instance, so a hardcoded
		// implementation matching the first test's numbers by coincidence
		// would fail here.
		RetrievalService otherService = new RetrievalService(vectorStore,
				new RagProperties(3, 0.9, 2000, 200, 0.1, 300, 200000));

		otherService.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getTopK()).isEqualTo(3);
		assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.9);
	}

	@Test
	void queryTextIsPassedThroughToTheRequest() {
		service.retrieve("proj-1", "what is the deployment process?", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		assertThat(captor.getValue().getQuery()).isEqualTo("what is the deployment process?");
	}

	@Test
	void narrowsToTheGivenDocumentsWhenDocumentIdsIsNonEmpty() {
		service.retrieve("proj-1", "any question", List.of("doc-1", "doc-2"));

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		Filter.Expression expression = captor.getValue().getFilterExpression();

		assertThat(expression.type()).isEqualTo(Filter.ExpressionType.AND);

		Filter.Expression projectClause = (Filter.Expression) expression.left();
		assertThat(projectClause.type()).isEqualTo(Filter.ExpressionType.EQ);
		assertThat(((Filter.Key) projectClause.left()).key()).isEqualTo("projectId");
		assertThat(((Filter.Value) projectClause.right()).value()).isEqualTo("proj-1");

		Filter.Expression documentClause = (Filter.Expression) expression.right();
		assertThat(documentClause.type()).isEqualTo(Filter.ExpressionType.IN);
		assertThat(((Filter.Key) documentClause.left()).key()).isEqualTo("documentId");
		assertThat(((Filter.Value) documentClause.right()).value()).isEqualTo(List.of("doc-1", "doc-2"));
	}

	@Test
	void anEmptyDocumentIdsListProducesExactlyTheSameFilterAsBefore() {
		// Guards against the compound-filter code path accidentally
		// changing the whole-project case's filter shape too.
		service.retrieve("proj-1", "any question", List.of());

		ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
		verify(vectorStore).similaritySearch(captor.capture());
		String filter = captor.getValue().getFilterExpression().toString();
		assertThat(filter).contains("proj-1");
		assertThat(filter).doesNotContain("documentId");
	}
}
