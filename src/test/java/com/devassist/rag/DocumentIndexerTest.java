package com.devassist.rag;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import com.devassist.document.Document;
import com.devassist.document.DocumentDeletedEvent;
import com.devassist.document.DocumentIngestedEvent;
import com.devassist.document.DocumentUpdatedEvent;
import com.devassist.document.SourceType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
		assertThat(chunk.getMetadata()).containsEntry("title", "notes.md");
		assertThat(chunk.getMetadata()).containsEntry("sourceType", "MARKDOWN");
		assertThat(chunk.getMetadata()).containsEntry("chunkIndex", 0);
	}

	@Test
	void assignsSequentialChunkIndexesAcrossMultipleChunks() {
		// chunkSizeChars is 2000; each paragraph block below is ~750 chars, so
		// three of them force the chunker past a single chunk and prove
		// chunkIndex actually increments rather than only ever being 0.
		String longContent = "Paragraph one. ".repeat(50) + "\n\n" + "Paragraph two. ".repeat(50) + "\n\n"
				+ "Paragraph three. ".repeat(50);
		Document longDocument = new Document("doc-2", "proj-1", "long.md", SourceType.MARKDOWN,
				longContent, "hash-2", Instant.now());

		indexer.onIngested(new DocumentIngestedEvent(longDocument));

		ArgumentCaptor<List<org.springframework.ai.document.Document>> captor =
				ArgumentCaptor.forClass(List.class);
		verify(vectorStore).add(captor.capture());
		List<org.springframework.ai.document.Document> chunks = captor.getValue();
		assertThat(chunks.size()).isGreaterThan(1);
		for (int i = 0; i < chunks.size(); i++) {
			assertThat(chunks.get(i).getMetadata()).containsEntry("chunkIndex", i);
		}
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

		// Plain verify() on both calls would pass even if add() ran first;
		// InOrder is what actually pins delete-before-add per BR-04.
		InOrder inOrder = inOrder(vectorStore);
		inOrder.verify(vectorStore).delete(any(Filter.Expression.class));
		inOrder.verify(vectorStore).add(any());
	}

	@Test
	void deletesChunksOnDocumentDeletion() {
		indexer.onDeleted(new DocumentDeletedEvent("proj-1", "doc-1"));

		verify(vectorStore).delete(any(Filter.Expression.class));
		assertThat(statusService.get("doc-1")).isEmpty();
	}

	@Test
	void abortsUpdateAndRecordsFailedWithoutIndexingWhenPriorChunkDeleteFails() {
		doThrow(new RuntimeException("vector store down")).when(vectorStore).delete(any(Filter.Expression.class));

		indexer.onUpdated(new DocumentUpdatedEvent(document));

		verify(vectorStore, never()).add(any());
		assertThat(statusService.get("doc-1")).hasValueSatisfying(status -> {
			assertThat(status.state()).isEqualTo(IndexStatus.State.FAILED);
			assertThat(status.reason()).contains("vector store down");
		});
	}

	@Test
	void keepsExistingStatusWhenDeleteFailsOnDocumentDeletion() {
		statusService.record(IndexStatus.indexed("doc-1", 3));
		doThrow(new RuntimeException("vector store down")).when(vectorStore).delete(any(Filter.Expression.class));

		indexer.onDeleted(new DocumentDeletedEvent("proj-1", "doc-1"));

		assertThat(statusService.get("doc-1")).hasValueSatisfying(status ->
				assertThat(status.state()).isEqualTo(IndexStatus.State.INDEXED));
	}
}
