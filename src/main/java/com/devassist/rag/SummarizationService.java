package com.devassist.rag;

import org.springframework.stereotype.Service;

import com.devassist.document.Document;
import com.devassist.document.DocumentService;

@Service
public class SummarizationService {

	private final DocumentService documentService;
	private final SummaryGenerator generator;
	private final RagProperties properties;

	public SummarizationService(DocumentService documentService, SummaryGenerator generator,
			RagProperties properties) {
		this.documentService = documentService;
		this.generator = generator;
		this.properties = properties;
	}

	public SummaryResponse summarize(String projectId, String documentId, String instruction) {
		long startedAt = System.currentTimeMillis();
		Document document = documentService.findById(projectId, documentId);

		// BR-13: the stored text is authoritative. Chunks overlap, so rebuilding
		// from them would feed every overlap region to the model twice.
		String content = document.content();
		boolean truncated = content.length() > properties.summaryMaxChars();
		String input = truncated ? content.substring(0, properties.summaryMaxChars()) : content;

		String summary = generator.summarize(input, instruction);
		return new SummaryResponse(document.id(), document.title(), summary, input.length(), truncated,
				System.currentTimeMillis() - startedAt);
	}
}
