package com.devassist.rag;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.devassist.project.ProjectService;

@Service
public class RagQueryService {

	// BR-09: fixed wording, exposed for GenerationService's system prompt to
	// interpolate so the two call sites can never drift apart (see
	// GenerationServiceTest.systemPromptEmbedsTheSharedRefusalConstant).
	public static final String NO_CONTEXT_ANSWER = "I don't have enough information to answer this.";

	private final ProjectService projectService;
	private final RetrievalService retrievalService;
	private final ContextBuilder contextBuilder;
	private final GenerationService generationService;

	public RagQueryService(ProjectService projectService, RetrievalService retrievalService,
			ContextBuilder contextBuilder, GenerationService generationService) {
		this.projectService = projectService;
		this.retrievalService = retrievalService;
		this.contextBuilder = contextBuilder;
		this.generationService = generationService;
	}

	public RagAnswerResponse answer(String projectId, String question) {
		long startedAt = System.currentTimeMillis();
		projectService.findById(projectId);

		List<Document> chunks = retrievalService.retrieve(projectId, question);
		if (chunks.isEmpty()) {
			// BR-08: no model call at all — nothing retrieved means nothing to ground on.
			return RagAnswerResponse.of(question, NO_CONTEXT_ANSWER,
					RagAnswerResponse.Status.INSUFFICIENT_CONTEXT, List.of(),
					System.currentTimeMillis() - startedAt);
		}

		String answer = generationService.generate(contextBuilder.buildContext(chunks), question);
		return RagAnswerResponse.of(question, answer, RagAnswerResponse.Status.ANSWERED,
				contextBuilder.toSources(chunks, answer), System.currentTimeMillis() - startedAt);
	}
}
