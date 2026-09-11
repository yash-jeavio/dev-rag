package com.devassist.document;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectNotFoundException;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentServiceTest {

	private final ProjectService projectService = new ProjectService();
	private final DocumentTextExtractor textExtractor = new DocumentTextExtractor();
	private final DocumentService documentService = new DocumentService(projectService, textExtractor);

	private String existingProjectId;

	@BeforeEach
	void createProject() {
		Project project = projectService
				.create(new CreateProjectRequest("DevAssist", null, "Java", "https://github.com/example/repo"));
		existingProjectId = project.id();
	}

	@Test
	void ingestFileStoresPlainTextDocument() {
		Document document = documentService.ingestFile(existingProjectId, "notes.txt",
				"Hello world".getBytes(StandardCharsets.UTF_8));

		assertThat(document.id()).isNotBlank();
		assertThat(document.projectId()).isEqualTo(existingProjectId);
		assertThat(document.title()).isEqualTo("notes.txt");
		assertThat(document.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(document.content()).isEqualTo("Hello world");
	}

	@Test
	void ingestFileAssignsMarkdownSourceTypeFromExtension() {
		Document document = documentService.ingestFile(existingProjectId, "README.md",
				"# Title".getBytes(StandardCharsets.UTF_8));

		assertThat(document.sourceType()).isEqualTo(SourceType.MARKDOWN);
	}

	@Test
	void ingestFileRejectsEmptyFile() {
		assertThatThrownBy(() -> documentService.ingestFile(existingProjectId, "empty.txt", new byte[0]))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void ingestFileRejectsFileOverTenMegabytes() {
		byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];

		assertThatThrownBy(() -> documentService.ingestFile(existingProjectId, "big.txt", tooLarge))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void ingestFileThrowsWhenProjectDoesNotExist() {
		assertThatThrownBy(() -> documentService.ingestFile("unknown-project", "notes.txt",
				"Hello".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ProjectNotFoundException.class);
	}

	@Test
	void ingestTextStoresDocumentWithTextSourceType() {
		Document document = documentService.ingestText(existingProjectId,
				new IngestTextRequest("Meeting Notes", "We discussed the roadmap."));

		assertThat(document.title()).isEqualTo("Meeting Notes");
		assertThat(document.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(document.content()).isEqualTo("We discussed the roadmap.");
	}

	@Test
	void ingestTextRejectsContentOverTenMegabytes() {
		String tooLarge = "a".repeat(10 * 1024 * 1024 + 1);

		assertThatThrownBy(
				() -> documentService.ingestText(existingProjectId, new IngestTextRequest("Big", tooLarge)))
				.isInstanceOf(InvalidDocumentException.class);
	}

	@Test
	void ingestTextThrowsWhenProjectDoesNotExist() {
		assertThatThrownBy(() -> documentService.ingestText("unknown-project", new IngestTextRequest("Title", "Body")))
				.isInstanceOf(ProjectNotFoundException.class);
	}

	@Test
	void findByIdReturnsPreviouslyIngestedDocument() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		Document found = documentService.findById(existingProjectId, created.id());

		assertThat(found).isEqualTo(created);
	}

	@Test
	void findByIdThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.findById(existingProjectId, "unknown-id"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void findByIdThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.findById(otherProject.id(), created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void findByIdThrowsProjectNotFoundWhenProjectDoesNotExist() {
		assertThatThrownBy(() -> documentService.findById("unknown-project", "unknown-id"))
				.isInstanceOf(ProjectNotFoundException.class);
	}

	@Test
	void updateFileReplacesContentAndPreservesIdAndCreatedAt() {
		Document created = documentService.ingestFile(existingProjectId, "notes.txt",
				"Original".getBytes(StandardCharsets.UTF_8));

		Document updated = documentService.updateFile(existingProjectId, created.id(), "revised.md",
				"Revised".getBytes(StandardCharsets.UTF_8));

		assertThat(updated.id()).isEqualTo(created.id());
		assertThat(updated.projectId()).isEqualTo(existingProjectId);
		assertThat(updated.title()).isEqualTo("revised.md");
		assertThat(updated.sourceType()).isEqualTo(SourceType.MARKDOWN);
		assertThat(updated.content()).isEqualTo("Revised");
		assertThat(updated.createdAt()).isEqualTo(created.createdAt());
		assertThat(documentService.findById(existingProjectId, created.id())).isEqualTo(updated);
	}

	@Test
	void updateFileThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.updateFile(existingProjectId, "unknown-id", "notes.txt",
				"Body".getBytes(StandardCharsets.UTF_8))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void updateFileThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestFile(existingProjectId, "notes.txt",
				"Original".getBytes(StandardCharsets.UTF_8));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.updateFile(otherProject.id(), created.id(), "notes.txt",
				"Body".getBytes(StandardCharsets.UTF_8))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void updateTextReplacesTitleAndContent() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		Document updated = documentService.updateText(existingProjectId, created.id(),
				new IngestTextRequest("Renamed", "Updated body"));

		assertThat(updated.id()).isEqualTo(created.id());
		assertThat(updated.projectId()).isEqualTo(existingProjectId);
		assertThat(updated.title()).isEqualTo("Renamed");
		assertThat(updated.content()).isEqualTo("Updated body");
		assertThat(updated.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(updated.createdAt()).isEqualTo(created.createdAt());
	}

	@Test
	void updateTextThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.updateText(existingProjectId, "unknown-id",
				new IngestTextRequest("Title", "Body"))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void updateTextThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.updateText(otherProject.id(), created.id(),
				new IngestTextRequest("Title", "Body"))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteRemovesDocument() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		documentService.delete(existingProjectId, created.id());

		assertThatThrownBy(() -> documentService.findById(existingProjectId, created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteThrowsWhenDocumentDoesNotExist() {
		assertThatThrownBy(() -> documentService.delete(existingProjectId, "unknown-id"))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteThrowsWhenDocumentBelongsToDifferentProject() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.delete(otherProject.id(), created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void ingestingIdenticalContentTwiceReturnsTheSameDocument() {
		Document first = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "same body"));
		Document second = documentService.ingestText(existingProjectId,
				new IngestTextRequest("notes again", "same body"));

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.title()).isEqualTo("notes");
	}

	@Test
	void identicalContentInDifferentProjectsCreatesTwoDocuments() {
		String otherProjectId = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://example.com/x")).id();

		Document first = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "same body"));
		Document second = documentService.ingestText(otherProjectId, new IngestTextRequest("notes", "same body"));

		assertThat(second.id()).isNotEqualTo(first.id());
	}

	@Test
	void differentContentCreatesDistinctDocuments() {
		Document first = documentService.ingestText(existingProjectId, new IngestTextRequest("a", "body one"));
		Document second = documentService.ingestText(existingProjectId, new IngestTextRequest("b", "body two"));

		assertThat(second.id()).isNotEqualTo(first.id());
	}
}
