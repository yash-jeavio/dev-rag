package com.devassist.rag;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/documents/{documentId}/summarize")
public class SummarizationController {

	private final SummarizationService summarizationService;

	public SummarizationController(SummarizationService summarizationService) {
		this.summarizationService = summarizationService;
	}

	@PostMapping
	public ResponseEntity<SummaryResponse> summarize(@PathVariable String projectId,
			@PathVariable String documentId,
			@Valid @RequestBody(required = false) SummarizeRequest request) {
		String instruction = (request != null) ? request.instruction() : null;
		return ResponseEntity.ok(summarizationService.summarize(projectId, documentId, instruction));
	}
}
