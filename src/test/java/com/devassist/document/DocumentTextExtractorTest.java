package com.devassist.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {

	private final DocumentTextExtractor extractor = new DocumentTextExtractor();

	@Test
	void extractsPlainTextFile() {
		ExtractedContent result = extractor.extract("notes.txt", "Hello world".getBytes(StandardCharsets.UTF_8));

		assertThat(result.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(result.text()).isEqualTo("Hello world");
	}

	@Test
	void extractsMarkdownFile() {
		ExtractedContent result = extractor.extract("README.md", "# Title".getBytes(StandardCharsets.UTF_8));

		assertThat(result.sourceType()).isEqualTo(SourceType.MARKDOWN);
		assertThat(result.text()).isEqualTo("# Title");
	}

	@Test
	void extractsPdfFile() throws IOException {
		byte[] pdfBytes = samplePdfBytes("Hello PDF");

		ExtractedContent result = extractor.extract("sample.pdf", pdfBytes);

		assertThat(result.sourceType()).isEqualTo(SourceType.PDF);
		assertThat(result.text()).contains("Hello PDF");
	}

	@Test
	void throwsForUnsupportedExtension() {
		assertThatThrownBy(() -> extractor.extract("archive.zip", new byte[] { 1, 2, 3 }))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void throwsForCorruptPdf() {
		assertThatThrownBy(() -> extractor.extract("broken.pdf", "not a pdf".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(InvalidDocumentException.class);
	}

	private static byte[] samplePdfBytes(String text) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
				contentStream.beginText();
				contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				contentStream.newLineAtOffset(50, 700);
				contentStream.showText(text);
				contentStream.endText();
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
	}
}
