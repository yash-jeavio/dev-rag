package com.devassist.document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

	public ExtractedContent extract(String filename, byte[] content) {
		String extension = extensionOf(filename);
		return switch (extension) {
			case "txt" -> new ExtractedContent(SourceType.TEXT, new String(content, StandardCharsets.UTF_8));
			case "md" -> new ExtractedContent(SourceType.MARKDOWN, new String(content, StandardCharsets.UTF_8));
			case "pdf" -> new ExtractedContent(SourceType.PDF, extractPdfText(filename, content));
			default -> throw new InvalidDocumentException("Unsupported file type: " + filename);
		};
	}

	private String extractPdfText(String filename, byte[] content) {
		try (PDDocument document = Loader.loadPDF(content)) {
			return new PDFTextStripper().getText(document);
		}
		catch (IOException e) {
			throw new InvalidDocumentException("Unable to extract text from PDF: " + filename, e);
		}
	}

	private String extensionOf(String filename) {
		int dotIndex = filename == null ? -1 : filename.lastIndexOf('.');
		if (dotIndex < 0 || dotIndex == filename.length() - 1) {
			throw new InvalidDocumentException("Unsupported file type: " + filename);
		}
		return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
	}
}
