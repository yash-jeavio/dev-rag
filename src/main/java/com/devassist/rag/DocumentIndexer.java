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
		// chunks are written would wipe out what was just added. A failed
		// delete must also abort the re-index rather than fall through: adding
		// new chunks on top of an undeleted old set would leave both
		// retrievable, and recording INDEXED afterwards would be a false
		// success with nothing but logs to reveal the mix-up.
		Document document = event.document();
		try {
			deleteChunks(document.id(), document.projectId());
		}
		catch (RuntimeException ex) {
			log.warn("Failed to delete prior chunks for document {}; aborting re-index", document.id(), ex);
			statusService.record(IndexStatus.failed(document.id(), "failed to delete previous chunks: " + ex.getMessage()));
			return;
		}
		index(document);
	}

	@EventListener
	public void onDeleted(DocumentDeletedEvent event) {
		// BR-05: a failed delete leaves orphan chunks retrievable with no
		// record of it, so the status entry is kept (never removed) rather
		// than erased on failure, and logged at ERROR rather than WARN. This
		// is an accepted limitation of the in-memory store, not a state to
		// model: the status endpoint 404s via documentService.findById before
		// it would ever read a status for a deleted document, so no new
		// status value here would be observable through the API anyway.
		try {
			deleteChunks(event.documentId(), event.projectId());
		}
		catch (RuntimeException ex) {
			log.error("Failed to delete chunks for document {}; orphan chunks remain in the vector store,"
					+ " discoverable only via this log", event.documentId(), ex);
			return;
		}
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

	// BR-06: filtering on documentId alone happened to be safe only because
	// document ids are random UUIDs; scoping the delete to projectId as well
	// matches every other vector-store operation and keeps this one from being
	// the sole exception if that assumption ever changes.
	private void deleteChunks(String documentId, String projectId) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		vectorStore.delete(
				builder.and(builder.eq("documentId", documentId), builder.eq("projectId", projectId)).build());
	}
}
