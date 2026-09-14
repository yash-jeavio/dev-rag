package com.devassist.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.devassist.document.Document;
import com.devassist.document.DocumentDeletedEvent;
import com.devassist.document.DocumentIngestedEvent;
import com.devassist.document.DocumentUpdatedEvent;

/**
 * Implements BR-04, BR-05 and BR-11: chunks a document's content, embeds each
 * chunk into the vector store, and tracks per-document index status.
 */
@Component
public class DocumentIndexer {

	private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

	private final ChunkingService chunkingService;
	private final VectorStore vectorStore;
	private final IndexStatusService statusService;

	public DocumentIndexer(ChunkingService chunkingService, VectorStore vectorStore,
			IndexStatusService statusService) {
		this.chunkingService = chunkingService;
		this.vectorStore = vectorStore;
		this.statusService = statusService;
	}

	@EventListener
	public void onIngested(DocumentIngestedEvent event) {
		index(event.document());
	}

	@EventListener
	public void onUpdated(DocumentUpdatedEvent event) {
		// BR-04: delete before re-indexing, or a delete issued after the new
		// chunks are written would wipe out what was just added.
		Document document = event.document();
		deleteChunks(document.id());
		index(document);
	}

	@EventListener
	public void onDeleted(DocumentDeletedEvent event) {
		deleteChunks(event.documentId());
		statusService.remove(event.documentId());
	}

	// BR-11: this listener runs synchronously on the ingest caller's thread,
	// and the document is already persisted by the time the event fires. A
	// propagated exception here would turn a successful upload into a 500, so
	// failures are caught and recorded as FAILED instead of being rethrown.
	private void index(Document document) {
		try {
			List<String> chunks = chunkingService.chunk(document.content());
			List<org.springframework.ai.document.Document> vectorDocuments = new ArrayList<>();
			for (int i = 0; i < chunks.size(); i++) {
				Map<String, Object> metadata = Map.of(
						"projectId", document.projectId(),
						"documentId", document.id(),
						"title", document.title(),
						"sourceType", document.sourceType().name(),
						"chunkIndex", i);
				vectorDocuments.add(new org.springframework.ai.document.Document(chunks.get(i), metadata));
			}
			if (!vectorDocuments.isEmpty()) {
				vectorStore.add(vectorDocuments);
			}
			statusService.record(IndexStatus.indexed(document.id(), vectorDocuments.size()));
		}
		catch (RuntimeException ex) {
			log.warn("Indexing failed for document {}", document.id(), ex);
			statusService.record(IndexStatus.failed(document.id(), ex.getMessage()));
		}
	}

	private void deleteChunks(String documentId) {
		try {
			vectorStore.delete(new FilterExpressionBuilder().eq("documentId", documentId).build());
		}
		catch (RuntimeException ex) {
			log.warn("Failed to delete chunks for document {}", documentId, ex);
		}
	}
}
