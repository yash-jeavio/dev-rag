package com.devassist.rag;

public record IndexStatusResponse(String documentId, String status, int chunkCount, String reason) {

	static IndexStatusResponse from(IndexStatus status) {
		return new IndexStatusResponse(status.documentId(), status.state().name(), status.chunkCount(),
				status.reason());
	}

	static IndexStatusResponse notIndexed(String documentId) {
		return new IndexStatusResponse(documentId, IndexStatus.State.FAILED.name(), 0, "Document has not been indexed");
	}
}
