package com.devassist.document;

import java.io.IOException;
import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
public class DocumentController {

	private final DocumentService documentService;

	public DocumentController(DocumentService documentService) {
		this.documentService = documentService;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<DocumentResponse> create(@PathVariable String projectId,
			@RequestParam("file") MultipartFile file) throws IOException {
		Document document = documentService.ingestFile(projectId, file.getOriginalFilename(), file.getBytes());
		return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/documents/" + document.id()))
				.body(DocumentResponse.from(document));
	}

	@PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentResponse> createFromText(@PathVariable String projectId,
			@Valid @RequestBody IngestTextRequest request) {
		Document document = documentService.ingestText(projectId, request);
		return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/documents/" + document.id()))
				.body(DocumentResponse.from(document));
	}

	@GetMapping("/{documentId}")
	public ResponseEntity<DocumentResponse> getById(@PathVariable String projectId,
			@PathVariable String documentId) {
		Document document = documentService.findById(projectId, documentId);
		return ResponseEntity.ok(DocumentResponse.from(document));
	}
}
