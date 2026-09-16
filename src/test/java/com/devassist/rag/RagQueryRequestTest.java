package com.devassist.rag;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	@Test
	void rejectsADocumentIdsListContainingANullElement() {
		assertThatThrownBy(() -> new RagQueryRequest("question", Arrays.asList("doc-1", null)))
				.isInstanceOf(NullPointerException.class);
	}
}
