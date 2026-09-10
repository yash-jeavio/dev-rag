package com.devassist.document;

import java.time.Instant;

public record DocumentResponse(String id, String projectId, String title, SourceType sourceType, String content,
		Instant createdAt) {

	public static DocumentResponse from(Document document) {
		return new DocumentResponse(document.id(), document.projectId(), document.title(), document.sourceType(),
				document.content(), document.createdAt());
	}
}
