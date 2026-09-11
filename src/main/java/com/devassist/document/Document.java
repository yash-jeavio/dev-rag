package com.devassist.document;

import java.time.Instant;

public record Document(
		String id,
		String projectId,
		String title,
		SourceType sourceType,
		String content,
		String contentHash,
		Instant createdAt
) {
}
