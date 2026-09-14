package com.devassist.document;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectNotFoundException;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DocumentServiceTest {

	private final ProjectService projectService = new ProjectService();
	private final DocumentTextExtractor textExtractor = new DocumentTextExtractor();
	private ApplicationEventPublisher eventPublisher;
	private DocumentService documentService;

	private String existingProjectId;

	@BeforeEach
	void createProject() {
		eventPublisher = mock(ApplicationEventPublisher.class);
		documentService = new DocumentService(projectService, textExtractor, eventPublisher);
		Project project = projectService
				.create(new CreateProjectRequest("DevAssist", null, "Java", "https://github.com/example/repo"));
		existingProjectId = project.id();
	}

	@Test
	void ingestFileStoresPlainTextDocument() {
		Document document = documentService.ingestFile(existingProjectId, "notes.txt",
				"Hello world".getBytes(StandardCharsets.UTF_8)).document();

		assertThat(document.id()).isNotBlank();
		assertThat(document.projectId()).isEqualTo(existingProjectId);
		assertThat(document.title()).isEqualTo("notes.txt");
		assertThat(document.sourceType()).isEqualTo(SourceType.TEXT);
		assertThat(document.content()).isEqualTo("Hello world");
	}

	@Test
	void ingestFileAssignsMarkdownSourceTypeFromExtension() {
		Document document = documentService.ingestFile(existingProjectId, "README.md",
				"# Title".getBytes(StandardCharsets.UTF_8)).document();

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
				new IngestTextRequest("Meeting Notes", "We discussed the roadmap.")).document();

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
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();

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
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();
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
				"Original".getBytes(StandardCharsets.UTF_8)).document();

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
				"Original".getBytes(StandardCharsets.UTF_8)).document();
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.updateFile(otherProject.id(), created.id(), "notes.txt",
				"Body".getBytes(StandardCharsets.UTF_8))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void updateTextReplacesTitleAndContent() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();

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
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.updateText(otherProject.id(), created.id(),
				new IngestTextRequest("Title", "Body"))).isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void deleteRemovesDocument() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();

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
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();
		Project otherProject = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://github.com/example/other"));

		assertThatThrownBy(() -> documentService.delete(otherProject.id(), created.id()))
				.isInstanceOf(DocumentNotFoundException.class);
	}

	@Test
	void ingestingIdenticalContentTwiceReturnsTheSameDocument() {
		Document first = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "same body"))
				.document();
		Document second = documentService
				.ingestText(existingProjectId, new IngestTextRequest("notes again", "same body")).document();

		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.title()).isEqualTo("notes");
	}

	@Test
	void identicalContentInDifferentProjectsCreatesTwoDocuments() {
		String otherProjectId = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://example.com/x")).id();

		Document first = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "same body"))
				.document();
		Document second = documentService.ingestText(otherProjectId, new IngestTextRequest("notes", "same body"))
				.document();

		assertThat(second.id()).isNotEqualTo(first.id());
	}

	@Test
	void differentContentCreatesDistinctDocuments() {
		Document first = documentService.ingestText(existingProjectId, new IngestTextRequest("a", "body one"))
				.document();
		Document second = documentService.ingestText(existingProjectId, new IngestTextRequest("b", "body two"))
				.document();

		assertThat(second.id()).isNotEqualTo(first.id());
	}

	@Test
	void ingestingNewContentReturnsResultWithDeduplicatedFalse() {
		IngestResult result = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"));

		assertThat(result.deduplicated()).isFalse();
	}

	@Test
	void ingestingIdenticalContentReturnsResultWithDeduplicatedTrueAndSameDocumentId() {
		IngestResult first = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "same body"));

		IngestResult second = documentService.ingestText(existingProjectId,
				new IngestTextRequest("notes again", "same body"));

		assertThat(second.deduplicated()).isTrue();
		assertThat(second.document().id()).isEqualTo(first.document().id());
	}

	@Test
	void updateTextDoesNotDedupAgainstAnotherDocumentWithMatchingContent() {
		Document other = documentService.ingestText(existingProjectId, new IngestTextRequest("Other", "shared body"))
				.document();
		Document target = documentService.ingestText(existingProjectId, new IngestTextRequest("Target", "original"))
				.document();

		Document updated = documentService.updateText(existingProjectId, target.id(),
				new IngestTextRequest("Target", "shared body"));

		assertThat(updated.id()).isEqualTo(target.id());
		assertThat(updated.content()).isEqualTo("shared body");

		Document otherStillPresent = documentService.findById(existingProjectId, other.id());
		assertThat(otherStillPresent.id()).isEqualTo(other.id());
		assertThat(otherStillPresent.content()).isEqualTo("shared body");
		assertThat(otherStillPresent.id()).isNotEqualTo(updated.id());
	}

	@Test
	void updateFileDoesNotDedupAgainstAnotherDocumentWithMatchingContent() {
		Document other = documentService
				.ingestFile(existingProjectId, "other.txt", "shared body".getBytes(StandardCharsets.UTF_8)).document();
		Document target = documentService
				.ingestFile(existingProjectId, "target.txt", "original".getBytes(StandardCharsets.UTF_8)).document();

		Document updated = documentService.updateFile(existingProjectId, target.id(), "target.txt",
				"shared body".getBytes(StandardCharsets.UTF_8));

		assertThat(updated.id()).isEqualTo(target.id());
		assertThat(updated.content()).isEqualTo("shared body");

		Document otherStillPresent = documentService.findById(existingProjectId, other.id());
		assertThat(otherStillPresent.id()).isEqualTo(other.id());
		assertThat(otherStillPresent.content()).isEqualTo("shared body");
		assertThat(otherStillPresent.id()).isNotEqualTo(updated.id());
	}

	@Test
	void publishesIngestedEventForNewDocument() {
		Document document = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "body"))
				.document();

		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue()).isInstanceOf(DocumentIngestedEvent.class);
		assertThat(((DocumentIngestedEvent) captor.getValue()).document().id()).isEqualTo(document.id());
	}

	@Test
	void publishesNoEventForDeduplicatedIngest() {
		documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "same body"));
		clearInvocations(eventPublisher);

		documentService.ingestText(existingProjectId, new IngestTextRequest("notes again", "same body"));

		verifyNoInteractions(eventPublisher);
	}

	@Test
	void publishesUpdatedEventOnUpdateText() {
		Document created = documentService.ingestText(existingProjectId, new IngestTextRequest("Title", "Body"))
				.document();
		clearInvocations(eventPublisher);

		Document updated = documentService.updateText(existingProjectId, created.id(),
				new IngestTextRequest("Renamed", "Updated body"));

		verify(eventPublisher).publishEvent(new DocumentUpdatedEvent(updated));
	}

	@Test
	void publishesUpdatedEventOnUpdateFile() {
		Document created = documentService.ingestFile(existingProjectId, "notes.txt",
				"Original".getBytes(StandardCharsets.UTF_8)).document();
		clearInvocations(eventPublisher);

		Document updated = documentService.updateFile(existingProjectId, created.id(), "revised.md",
				"Revised".getBytes(StandardCharsets.UTF_8));

		verify(eventPublisher).publishEvent(new DocumentUpdatedEvent(updated));
	}

	@Test
	void publishesDeletedEventOnDelete() {
		Document document = documentService.ingestText(existingProjectId, new IngestTextRequest("notes", "body"))
				.document();
		clearInvocations(eventPublisher);

		documentService.delete(existingProjectId, document.id());

		verify(eventPublisher).publishEvent(new DocumentDeletedEvent(existingProjectId, document.id()));
	}

	@Test
	void listsOnlyDocumentsBelongingToTheProject() {
		String otherProjectId = projectService
				.create(new CreateProjectRequest("Other", null, "Java", "https://example.com/x")).id();
		Document mine = documentService.ingestText(existingProjectId, new IngestTextRequest("mine", "body one"))
				.document();
		documentService.ingestText(otherProjectId, new IngestTextRequest("theirs", "body two"));

		List<Document> result = documentService.findByProject(existingProjectId);

		assertThat(result).extracting(Document::id).containsExactly(mine.id());
	}

	@Test
	void listingAnEmptyProjectReturnsEmptyListNotError() {
		assertThat(documentService.findByProject(existingProjectId)).isEmpty();
	}

	@Test
	void listingDocumentsOfUnknownProjectThrows() {
		assertThatThrownBy(() -> documentService.findByProject("no-such-project"))
				.isInstanceOf(ProjectNotFoundException.class);
	}
}
