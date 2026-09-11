package com.devassist.document;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

@Component
public class DocumentTextExtractor {

	private static final Map<String, SourceType> BY_EXTENSION = Map.ofEntries(
			Map.entry("txt", SourceType.TEXT),
			Map.entry("md", SourceType.MARKDOWN),
			Map.entry("pdf", SourceType.PDF),
			Map.entry("doc", SourceType.WORD),
			Map.entry("docx", SourceType.WORD),
			Map.entry("xls", SourceType.EXCEL),
			Map.entry("xlsx", SourceType.EXCEL),
			Map.entry("ppt", SourceType.POWERPOINT),
			Map.entry("pptx", SourceType.POWERPOINT),
			Map.entry("html", SourceType.HTML),
			Map.entry("htm", SourceType.HTML));

	private static final Map<String, SourceType> BY_MEDIA_TYPE = Map.ofEntries(
			Map.entry("text/plain", SourceType.TEXT),
			Map.entry("text/markdown", SourceType.MARKDOWN),
			Map.entry("application/pdf", SourceType.PDF),
			Map.entry("application/msword", SourceType.WORD),
			Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", SourceType.WORD),
			Map.entry("application/vnd.ms-excel", SourceType.EXCEL),
			Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", SourceType.EXCEL),
			Map.entry("application/vnd.ms-powerpoint", SourceType.POWERPOINT),
			Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",
					SourceType.POWERPOINT),
			Map.entry("text/html", SourceType.HTML));

	private final Tika tika;

	public DocumentTextExtractor() {
		this.tika = new Tika();
		// Tika truncates to 100,000 characters by default, which would silently
		// discard most of a large document. -1 disables the limit; the 10MB
		// upload cap in DocumentService is the real bound.
		this.tika.setMaxStringLength(-1);
	}

	public ExtractedContent extract(String filename, byte[] bytes) {
		SourceType byExtension = BY_EXTENSION.get(extension(filename));
		if (byExtension == null) {
			throw new InvalidDocumentException("Unsupported file type: " + filename);
		}

		String detected = tika.detect(bytes, filename);
		SourceType byContent = BY_MEDIA_TYPE.get(stripParameters(detected));

		// Markdown and HTML are both detected as text/* by content, so an
		// extension that agrees with a text-family detection is trusted.
		SourceType resolved = (byContent != null) ? byContent : byExtension;
		if (byContent != null && byContent != byExtension && !isTextFamily(byContent, byExtension)) {
			throw new InvalidDocumentException(
					"File content (" + detected + ") does not match extension: " + filename);
		}

		try {
			String text = tika.parseToString(new ByteArrayInputStream(bytes));
			if (text.isBlank()) {
				throw new InvalidDocumentException("No text could be extracted from: " + filename);
			}
			return new ExtractedContent(resolved == SourceType.TEXT ? byExtension : resolved, text.strip());
		}
		catch (InvalidDocumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new InvalidDocumentException("Failed to extract text from: " + filename);
		}
	}

	private boolean isTextFamily(SourceType a, SourceType b) {
		return isTextLike(a) && isTextLike(b);
	}

	private boolean isTextLike(SourceType type) {
		return type == SourceType.TEXT || type == SourceType.MARKDOWN || type == SourceType.HTML;
	}

	private String stripParameters(String mediaType) {
		int semicolon = mediaType.indexOf(';');
		return (semicolon < 0 ? mediaType : mediaType.substring(0, semicolon)).trim();
	}

	private String extension(String filename) {
		int dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) {
			return "";
		}
		return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
