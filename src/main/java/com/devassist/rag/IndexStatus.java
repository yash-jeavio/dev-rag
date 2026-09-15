package com.devassist.rag;

public record IndexStatus(String documentId, State state, int chunkCount, String reason) {

	public enum State {
		INDEXED,
		FAILED
	}

	public static IndexStatus indexed(String documentId, int chunkCount) {
		return new IndexStatus(documentId, State.INDEXED, chunkCount, null);
	}

	public static IndexStatus failed(String documentId, String reason) {
		return new IndexStatus(documentId, State.FAILED, 0, reason);
	}
}
