package com.devassist.document;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
			// Tika resolves a plain-text body under a .md filename to this more specific,
			// registered alias rather than the generic text/markdown.
			Map.entry("text/x-web-markdown", SourceType.MARKDOWN),
			Map.entry("application/pdf", SourceType.PDF),
			Map.entry("application/msword", SourceType.WORD),
			Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", SourceType.WORD),
			Map.entry("application/vnd.ms-excel", SourceType.EXCEL),
			Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", SourceType.EXCEL),
			Map.entry("application/vnd.ms-powerpoint", SourceType.POWERPOINT),
			Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation",
					SourceType.POWERPOINT),
			Map.entry("text/html", SourceType.HTML));

	// Generic sentinels Tika returns when content-based detection is genuinely inconclusive
	// (e.g. it couldn't identify anything more specific) - as opposed to a concrete, correctly
	// identified type that simply isn't one of the 7 accepted formats.
	private static final Set<String> INCONCLUSIVE_MEDIA_TYPES = Set.of(
			"application/octet-stream",
			"application/x-empty");

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

		String detected = stripParameters(tika.detect(bytes, filename));
		SourceType byContent = BY_MEDIA_TYPE.get(detected);

		SourceType resolved;
		if (byContent != null) {
			resolved = byContent;
			// Markdown and HTML are both detected as text/* by content, so an
			// extension that agrees with a text-family detection is trusted.
			if (byContent != byExtension && !isTextFamily(byContent, byExtension)) {
				throw new InvalidDocumentException(
						"File content (" + detected + ") does not match extension: " + filename);
			}
		}
		else if (INCONCLUSIVE_MEDIA_TYPES.contains(detected)) {
			// Detection genuinely couldn't tell - fall back to trusting the extension.
			resolved = byExtension;
		}
		else {
			// A concrete, correctly identified type that isn't one of the 7 accepted formats
			// (e.g. RTF, JPEG, ZIP) must be rejected outright, even if the extension is one we
			// accept and even if a parser could technically pull some text out of it (BR-15).
			throw new InvalidDocumentException(
					"File content (" + detected + ") is not an accepted document type: " + filename);
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
