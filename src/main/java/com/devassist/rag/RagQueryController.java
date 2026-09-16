package com.devassist.rag;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/query")
public class RagQueryController {

	private final RagQueryService queryService;

	public RagQueryController(RagQueryService queryService) {
		this.queryService = queryService;
	}

	@PostMapping
	public ResponseEntity<RagAnswerResponse> query(@PathVariable String projectId,
			@Valid @RequestBody RagQueryRequest request) {
		return ResponseEntity.ok(queryService.answer(projectId, request.question(), request.documentIds()));
	}
}
