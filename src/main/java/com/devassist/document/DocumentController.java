package com.devassist.document;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
		IngestResult result = documentService.ingestFile(projectId, file.getOriginalFilename(), file.getBytes());
		return ingestResponse(projectId, result);
	}

	@PostMapping(path = "/text", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentResponse> createFromText(@PathVariable String projectId,
			@Valid @RequestBody IngestTextRequest request) {
		IngestResult result = documentService.ingestText(projectId, request);
		return ingestResponse(projectId, result);
	}

	private ResponseEntity<DocumentResponse> ingestResponse(String projectId, IngestResult result) {
		Document document = result.document();
		DocumentResponse body = DocumentResponse.from(document, result.deduplicated());
		if (result.deduplicated()) {
			return ResponseEntity.ok(body);
		}
		return ResponseEntity.created(URI.create("/api/projects/" + projectId + "/documents/" + document.id()))
				.body(body);
	}

	@GetMapping("/{documentId}")
	public ResponseEntity<DocumentResponse> getById(@PathVariable String projectId,
			@PathVariable String documentId) {
		Document document = documentService.findById(projectId, documentId);
		return ResponseEntity.ok(DocumentResponse.from(document));
	}

	@GetMapping
	public ResponseEntity<List<DocumentResponse>> list(@PathVariable String projectId) {
		return ResponseEntity.ok(
				documentService.findByProject(projectId).stream().map(DocumentResponse::from).toList());
	}

	@PutMapping(path = "/{documentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<DocumentResponse> update(@PathVariable String projectId, @PathVariable String documentId,
			@RequestParam("file") MultipartFile file) throws IOException {
		Document document = documentService.updateFile(projectId, documentId, file.getOriginalFilename(),
				file.getBytes());
		return ResponseEntity.ok(DocumentResponse.from(document));
	}

	@PutMapping(path = "/{documentId}/text", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<DocumentResponse> updateFromText(@PathVariable String projectId,
			@PathVariable String documentId, @Valid @RequestBody IngestTextRequest request) {
		Document document = documentService.updateText(projectId, documentId, request);
		return ResponseEntity.ok(DocumentResponse.from(document));
	}

	@DeleteMapping("/{documentId}")
	public ResponseEntity<Void> delete(@PathVariable String projectId, @PathVariable String documentId) {
		documentService.delete(projectId, documentId);
		return ResponseEntity.noContent().build();
	}
}
