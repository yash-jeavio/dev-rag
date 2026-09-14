package com.devassist.rag;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devassist.document.DocumentService;

@RestController
@RequestMapping("/api/projects/{projectId}/documents/{documentId}/index-status")
public class IndexStatusController {

	private final DocumentService documentService;
	private final IndexStatusService statusService;

	public IndexStatusController(DocumentService documentService, IndexStatusService statusService) {
		this.documentService = documentService;
		this.statusService = statusService;
	}

	@GetMapping
	public ResponseEntity<IndexStatusResponse> get(@PathVariable String projectId, @PathVariable String documentId) {
		// Delegating the lookup gives the existing 404 behaviour for unknown
		// projects and for documents belonging to another project.
		documentService.findById(projectId, documentId);
		return ResponseEntity.ok(statusService.get(documentId)
				.map(IndexStatusResponse::from)
				.orElseGet(() -> IndexStatusResponse.notIndexed(documentId)));
	}
}
