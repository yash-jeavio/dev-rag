package com.devassist.document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.devassist.project.ProjectService;

@Service
public class DocumentService {

	private static final long MAX_CONTENT_BYTES = 10L * 1024 * 1024;

	private final Map<String, Document> documents = new ConcurrentHashMap<>();
	private final ProjectService projectService;
	private final DocumentTextExtractor textExtractor;

	public DocumentService(ProjectService projectService, DocumentTextExtractor textExtractor) {
		this.projectService = projectService;
		this.textExtractor = textExtractor;
	}

	public Document ingestFile(String projectId, String filename, byte[] fileBytes) {
		projectService.findById(projectId);
		validateFile(fileBytes);
		ExtractedContent extracted = textExtractor.extract(filename, fileBytes);
		Document document = new Document(UUID.randomUUID().toString(), projectId, filename, extracted.sourceType(),
				extracted.text(), Instant.now());
		documents.put(document.id(), document);
		return document;
	}

	public Document ingestText(String projectId, IngestTextRequest request) {
		projectService.findById(projectId);
		validateContentSize(request.content());
		Document document = new Document(UUID.randomUUID().toString(), projectId, request.title(), SourceType.TEXT,
				request.content(), Instant.now());
		documents.put(document.id(), document);
		return document;
	}

	public Document findById(String projectId, String documentId) {
		Document document = documents.get(documentId);
		if (document == null || !document.projectId().equals(projectId)) {
			throw new DocumentNotFoundException(documentId);
		}
		return document;
	}

	private void validateFile(byte[] fileBytes) {
		if (fileBytes.length == 0) {
			throw new InvalidDocumentException("File must not be empty");
		}
		if (fileBytes.length > MAX_CONTENT_BYTES) {
			throw new InvalidDocumentException("File exceeds the maximum allowed size of 10MB");
		}
	}

	private void validateContentSize(String content) {
		if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
			throw new InvalidDocumentException("Content exceeds the maximum allowed size of 10MB");
		}
	}
}
