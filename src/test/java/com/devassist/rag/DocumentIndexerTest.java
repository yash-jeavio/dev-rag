package com.devassist.rag;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.VectorStore;

import com.devassist.document.Document;
import com.devassist.document.DocumentDeletedEvent;
import com.devassist.document.DocumentIngestedEvent;
import com.devassist.document.DocumentUpdatedEvent;
import com.devassist.document.SourceType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentIndexerTest {

	private VectorStore vectorStore;
	private IndexStatusService statusService;
	private DocumentIndexer indexer;

	private final Document document = new Document("doc-1", "proj-1", "notes.md", SourceType.MARKDOWN,
			"body text", "hash-1", Instant.now());

	@BeforeEach
	void setUp() {
		vectorStore = mock(VectorStore.class);
		statusService = new IndexStatusService();
		RagProperties properties = new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000);
		indexer = new DocumentIndexer(new ChunkingService(properties), vectorStore, statusService);
	}

	@Test
	void writesChunksWithProjectAndDocumentMetadata() {
		indexer.onIngested(new DocumentIngestedEvent(document));

		ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
				ArgumentCaptor.forClass(List.class);
		verify(vectorStore).add(captor.capture());
		var chunk = captor.getValue().get(0);
		assertThat(chunk.getMetadata()).containsEntry("projectId", "proj-1");
		assertThat(chunk.getMetadata()).containsEntry("documentId", "doc-1");
		assertThat(chunk.getMetadata()).containsEntry("chunkIndex", 0);
	}

	@Test
	void recordsIndexedStatusWithChunkCount() {
		indexer.onIngested(new DocumentIngestedEvent(document));

		assertThat(statusService.get("doc-1")).hasValueSatisfying(status -> {
			assertThat(status.state()).isEqualTo(IndexStatus.State.INDEXED);
			assertThat(status.chunkCount()).isEqualTo(1);
		});
	}

	@Test
	void recordsFailedStatusInsteadOfPropagatingWhenEmbeddingFails() {
		doThrow(new RuntimeException("ollama down")).when(vectorStore).add(any());

		indexer.onIngested(new DocumentIngestedEvent(document));

		assertThat(statusService.get("doc-1")).hasValueSatisfying(status -> {
			assertThat(status.state()).isEqualTo(IndexStatus.State.FAILED);
			assertThat(status.reason()).contains("ollama down");
		});
	}

	@Test
	void deletesPriorChunksBeforeIndexingAnUpdate() {
		indexer.onUpdated(new DocumentUpdatedEvent(document));

		verify(vectorStore).delete(any(org.springframework.ai.vectorstore.filter.Filter.Expression.class));
		verify(vectorStore).add(any());
	}

	@Test
	void deletesChunksOnDocumentDeletion() {
		indexer.onDeleted(new DocumentDeletedEvent("proj-1", "doc-1"));

		verify(vectorStore).delete(any(org.springframework.ai.vectorstore.filter.Filter.Expression.class));
		assertThat(statusService.get("doc-1")).isEmpty();
	}
}
