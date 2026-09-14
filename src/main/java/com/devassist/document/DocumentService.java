package com.devassist.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
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

	public IngestResult ingestFile(String projectId, String filename, byte[] fileBytes) {
		projectService.findById(projectId);
		validateFile(fileBytes);
		ExtractedContent extracted = textExtractor.extract(filename, fileBytes);

		String contentHash = sha256(extracted.text());
		Optional<Document> existing = findByHash(projectId, contentHash);
		if (existing.isPresent()) {
			return new IngestResult(existing.get(), true);
		}

		Document document = new Document(UUID.randomUUID().toString(), projectId, filename, extracted.sourceType(),
				extracted.text(), contentHash, Instant.now());
		documents.put(document.id(), document);
		return new IngestResult(document, false);
	}

	public IngestResult ingestText(String projectId, IngestTextRequest request) {
		projectService.findById(projectId);
		validateContentSize(request.content());

		String contentHash = sha256(request.content());
		Optional<Document> existing = findByHash(projectId, contentHash);
		if (existing.isPresent()) {
			return new IngestResult(existing.get(), true);
		}

		Document document = new Document(UUID.randomUUID().toString(), projectId, request.title(), SourceType.TEXT,
				request.content(), contentHash, Instant.now());
		documents.put(document.id(), document);
		return new IngestResult(document, false);
	}

	public Document findById(String projectId, String documentId) {
		projectService.findById(projectId);
		Document document = documents.get(documentId);
		if (document == null || !document.projectId().equals(projectId)) {
			throw new DocumentNotFoundException(documentId);
		}
		return document;
	}

	public Document updateFile(String projectId, String documentId, String filename, byte[] fileBytes) {
		Document existing = findById(projectId, documentId);
		validateFile(fileBytes);
		ExtractedContent extracted = textExtractor.extract(filename, fileBytes);
		Document updated = new Document(existing.id(), existing.projectId(), filename, extracted.sourceType(),
				extracted.text(), sha256(extracted.text()), existing.createdAt());
		documents.put(updated.id(), updated);
		return updated;
	}

	public Document updateText(String projectId, String documentId, IngestTextRequest request) {
		Document existing = findById(projectId, documentId);
		validateContentSize(request.content());
		Document updated = new Document(existing.id(), existing.projectId(), request.title(), SourceType.TEXT,
				request.content(), sha256(request.content()), existing.createdAt());
		documents.put(updated.id(), updated);
		return updated;
	}

	public void delete(String projectId, String documentId) {
		Document existing = findById(projectId, documentId);
		documents.remove(existing.id());
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

	private static String sha256(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required but unavailable", ex);
		}
	}

	private Optional<Document> findByHash(String projectId, String contentHash) {
		return documents.values().stream()
				.filter(document -> document.projectId().equals(projectId))
				.filter(document -> document.contentHash().equals(contentHash))
				.findFirst();
	}
}
