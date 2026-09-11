package com.devassist.document;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
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

	@Test
	void extractsDocxContent() throws Exception {
		byte[] bytes = sampleDocxBytes("DevAssist sample");

		ExtractedContent result = extractor.extract("sample.docx", bytes);

		assertThat(result.sourceType()).isEqualTo(SourceType.WORD);
		assertThat(result.text()).contains("DevAssist sample");
	}

	@Test
	void extractsXlsxContent() throws Exception {
		byte[] bytes = sampleXlsxBytes("DevAssist sample");

		ExtractedContent result = extractor.extract("sample.xlsx", bytes);

		assertThat(result.sourceType()).isEqualTo(SourceType.EXCEL);
		assertThat(result.text()).contains("DevAssist sample");
	}

	@Test
	void extractsPptxContent() throws Exception {
		byte[] bytes = samplePptxBytes("DevAssist sample");

		ExtractedContent result = extractor.extract("sample.pptx", bytes);

		assertThat(result.sourceType()).isEqualTo(SourceType.POWERPOINT);
		assertThat(result.text()).contains("DevAssist sample");
	}

	@Test
	void extractsHtmlContentAsPlainText() {
		byte[] bytes = "<html><body><h1>Title</h1><p>Body text</p></body></html>"
				.getBytes(StandardCharsets.UTF_8);

		ExtractedContent result = extractor.extract("page.html", bytes);

		assertThat(result.sourceType()).isEqualTo(SourceType.HTML);
		assertThat(result.text()).contains("Body text");
		assertThat(result.text()).doesNotContain("<p>");
	}

	@Test
	void rejectsFileWhoseDetectedTypeContradictsItsExtension() throws Exception {
		byte[] pdfBytes = samplePdfBytes("Hello PDF");

		assertThatThrownBy(() -> extractor.extract("notes.txt", pdfBytes))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void rejectsUnsupportedExtension() {
		assertThatThrownBy(() -> extractor.extract("archive.zip", new byte[] { 1, 2, 3 }))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void rejectsConcreteDetectedTypeNotInAcceptedSetEvenWithAcceptedExtension() {
		// A well-formed RTF body: Tika detects application/rtf (a concrete, non-inconclusive
		// type outside the 7 accepted types) AND its RTF parser successfully extracts readable
		// text ("Hello RTF"). That combination is what exposes the bug: a detection-vs-extension
		// reconciliation that only rejects mismatches when the detected type happens to be one
		// of the accepted ones would let this through as TEXT, because parsing "succeeds" with
		// non-blank output. The type must be rejected before extraction is even attempted.
		byte[] rtfBytes = "{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0\\fs24 Hello RTF\\par}"
				.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> extractor.extract("notes.txt", rtfBytes))
				.isInstanceOf(InvalidDocumentException.class);
	}

	private static byte[] sampleDocxBytes(String text) throws IOException {
		try (XWPFDocument document = new XWPFDocument()) {
			XWPFParagraph paragraph = document.createParagraph();
			XWPFRun run = paragraph.createRun();
			run.setText(text);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			document.write(out);
			return out.toByteArray();
		}
	}

	private static byte[] sampleXlsxBytes(String text) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet();
			Row row = sheet.createRow(0);
			Cell cell = row.createCell(0);
			cell.setCellValue(text);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			return out.toByteArray();
		}
	}

	private static byte[] samplePptxBytes(String text) throws IOException {
		try (XMLSlideShow slideShow = new XMLSlideShow()) {
			XSLFSlide slide = slideShow.createSlide();
			XSLFTextBox textBox = slide.createTextBox();
			textBox.setAnchor(new Rectangle(50, 50, 200, 50));
			textBox.setText(text);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			slideShow.write(out);
			return out.toByteArray();
		}
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
