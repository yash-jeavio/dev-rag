package com.devassist.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagQueryRequest(@NotBlank @Size(max = 1000) String question, List<String> documentIds) {

	// Jackson passes null when the field is omitted from the request body -
	// normalizing here means RetrievalService and everything else can just
	// check isEmpty(), never null, for "search the whole project" (BR-01,
	// specs/document-scoped-retrieval.md).
	public RagQueryRequest {
		documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
	}
}
