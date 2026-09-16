package com.devassist.rag;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagQueryServiceTest {

	private RetrievalService retrievalService;
	private GenerationService generationService;
	private RagQueryService service;

	@BeforeEach
	void setUp() {
		retrievalService = mock(RetrievalService.class);
		generationService = mock(GenerationService.class);
		ProjectService projectService = mock(ProjectService.class);
		service = new RagQueryService(projectService, retrievalService,
				new ContextBuilder(new RagProperties(5, 0.5, 2000, 200, 0.1, 300, 200000)),
				generationService);
	}

	@Test
	void neverCallsTheModelWhenNothingIsRetrieved() {
		when(retrievalService.retrieve(anyString(), anyString(), any())).thenReturn(List.of());

		RagAnswerResponse response = service.answer("proj-1", "unanswerable question");

		assertThat(response.status()).isEqualTo(RagAnswerResponse.Status.INSUFFICIENT_CONTEXT);
		assertThat(response.sources()).isEmpty();
		verify(generationService, never()).generate(anyString(), anyString());
	}

	@Test
	void returnsAnsweredWithSourcesWhenChunksAreRetrieved() {
		when(retrievalService.retrieve(anyString(), anyString(), any())).thenReturn(List.of(
				new Document("refund text", Map.of("projectId", "proj-1", "documentId", "doc-1",
						"title", "policy.pdf", "sourceType", "PDF", "chunkIndex", 0))));
		when(generationService.generate(anyString(), anyString())).thenReturn("Within 30 days. [1]");

		RagAnswerResponse response = service.answer("proj-1", "refund window?");

		assertThat(response.status()).isEqualTo(RagAnswerResponse.Status.ANSWERED);
		assertThat(response.sources()).hasSize(1);
		assertThat(response.sources().get(0).cited()).isTrue();
		assertThat(response.evaluation()).isNull();
	}

	@Test
	void passesDocumentIdsThroughToRetrieval() {
		when(retrievalService.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of());

		service.answer("proj-1", "question", List.of("doc-1", "doc-2"));

		verify(retrievalService).retrieve("proj-1", "question", List.of("doc-1", "doc-2"));
	}

	@Test
	void theTwoArgOverloadDelegatesWithAnEmptyDocumentIdsList() {
		when(retrievalService.retrieve(anyString(), anyString(), anyList())).thenReturn(List.of());

		service.answer("proj-1", "question");

		verify(retrievalService).retrieve("proj-1", "question", List.of());
	}
}
