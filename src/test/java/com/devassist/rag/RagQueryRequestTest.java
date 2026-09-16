package com.devassist.rag;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagQueryRequestTest {

	@Test
	void normalizesANullDocumentIdsToAnEmptyList() {
		RagQueryRequest request = new RagQueryRequest("question", null);

		assertThat(request.documentIds()).isEmpty();
	}

	@Test
	void preservesAProvidedDocumentIdsList() {
		RagQueryRequest request = new RagQueryRequest("question", List.of("doc-1", "doc-2"));

		assertThat(request.documentIds()).containsExactly("doc-1", "doc-2");
	}
}
