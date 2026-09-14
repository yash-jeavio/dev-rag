package com.devassist.rag;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.devassist.document.Document;
import com.devassist.document.DocumentService;
import com.devassist.document.SourceType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SummarizationServiceTest {

	private DocumentService documentService;
	private SummaryGenerator generator;
	private SummarizationService service;

	@BeforeEach
	void setUp() {
		documentService = mock(DocumentService.class);
		generator = mock(SummaryGenerator.class);
		// any() (not anyString()) for the instruction: these tests call
		// summarize(..., null), and Mockito's anyString() only matches a real
		// String instance, so it silently fails to stub a null argument.
		when(generator.summarize(anyString(), any())).thenReturn("a summary");
		service = new SummarizationService(documentService, generator,
				new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 100));
	}

	private void stubDocument(String content) {
		when(documentService.findById(anyString(), anyString())).thenReturn(
				new Document("doc-1", "proj-1", "notes.md", SourceType.MARKDOWN, content, "hash", Instant.now()));
	}

	// BR-13 is enforced structurally: SummarizationService takes no VectorStore
	// at all, so it cannot consult one. A mock-based "never called" assertion
	// would be vacuous — this test pins the behaviour, the constructor pins the
	// guarantee.
	@Test
	void summarisesStoredContentDirectly() {
		stubDocument("short content");

		SummaryResponse response = service.summarize("proj-1", "doc-1", null);

		assertThat(response.summary()).isEqualTo("a summary");
		assertThat(response.truncated()).isFalse();
		assertThat(response.charactersUsed()).isEqualTo("short content".length());
		assertThat(response.documentId()).isEqualTo("doc-1");
		assertThat(response.title()).isEqualTo("notes.md");
	}

	@Test
	void truncatesAndReportsItWhenContentExceedsBudget() {
		stubDocument("x".repeat(500));

		SummaryResponse response = service.summarize("proj-1", "doc-1", null);

		assertThat(response.truncated()).isTrue();
		assertThat(response.charactersUsed()).isEqualTo(100);
	}

	// BR-14 boundary: content exactly at summary-max-chars must NOT be reported
	// as truncated — nothing was actually dropped.
	@Test
	void contentExactlyAtBudgetIsNotTruncated() {
		stubDocument("x".repeat(100));

		SummaryResponse response = service.summarize("proj-1", "doc-1", null);

		assertThat(response.truncated()).isFalse();
		assertThat(response.charactersUsed()).isEqualTo(100);
	}

	// BR-14 boundary: one character over must be reported as truncated —
	// off-by-one here would either lie about truncation or over-truncate.
	@Test
	void contentOneCharacterOverBudgetIsTruncated() {
		stubDocument("x".repeat(101));

		SummaryResponse response = service.summarize("proj-1", "doc-1", null);

		assertThat(response.truncated()).isTrue();
		assertThat(response.charactersUsed()).isEqualTo(100);
	}

	@Test
	void emptyContentIsSummarisedWithoutTruncation() {
		stubDocument("");

		SummaryResponse response = service.summarize("proj-1", "doc-1", null);

		assertThat(response.truncated()).isFalse();
		assertThat(response.charactersUsed()).isEqualTo(0);
	}

	@Test
	void blankContentIsSummarisedWithoutTruncation() {
		stubDocument("   ");

		SummaryResponse response = service.summarize("proj-1", "doc-1", null);

		assertThat(response.truncated()).isFalse();
		assertThat(response.charactersUsed()).isEqualTo(3);
	}

	@Test
	void absentInstructionIsPassedThroughAsNull() {
		stubDocument("short content");

		service.summarize("proj-1", "doc-1", null);

		org.mockito.Mockito.verify(generator).summarize("short content", null);
	}
}
