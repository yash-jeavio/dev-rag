package com.devassist.eval;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.DefaultResourceLoader;

import com.devassist.document.DocumentService;
import com.devassist.document.IngestTextRequest;
import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalCorpusSeederTest {

	private final EvalProperties properties = new EvalProperties("RAG Evaluation Corpus",
			"classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);

	@Test
	void createsTheProjectAndIngestsBothDocumentsWhenNoneExists() throws Exception {
		ProjectService projectService = mock(ProjectService.class);
		DocumentService documentService = mock(DocumentService.class);
		Project created = new Project("eval-proj-1", "RAG Evaluation Corpus", "desc", "N/A",
				"https://example.com/devassist-eval");
		when(projectService.findAll()).thenReturn(List.of());
		when(projectService.create(any(CreateProjectRequest.class))).thenReturn(created);
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);
		EvalCorpusSeeder seeder = new EvalCorpusSeeder(projectService, documentService, locator, properties,
				new DefaultResourceLoader());

		seeder.run(mock(ApplicationArguments.class));

		verify(documentService, times(2)).ingestText(eq("eval-proj-1"), any(IngestTextRequest.class));
	}

	@Test
	void reusesAnExistingEvalProjectRatherThanCreatingAnother() throws Exception {
		ProjectService projectService = mock(ProjectService.class);
		DocumentService documentService = mock(DocumentService.class);
		Project existing = new Project("eval-proj-1", "RAG Evaluation Corpus", "desc", "N/A",
				"https://example.com/devassist-eval");
		when(projectService.findAll()).thenReturn(List.of(existing));
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);
		EvalCorpusSeeder seeder = new EvalCorpusSeeder(projectService, documentService, locator, properties,
				new DefaultResourceLoader());

		seeder.run(mock(ApplicationArguments.class));

		verify(projectService, times(0)).create(any(CreateProjectRequest.class));
		verify(documentService, times(2)).ingestText(eq("eval-proj-1"), any(IngestTextRequest.class));
	}

	// BR-01: a failure here (e.g. Ollama unreachable, surfacing when the
	// DocumentIngestedEvent listener tries to embed) must never propagate out
	// of run() and fail application startup.
	@Test
	void neverPropagatesAFailureFromIngestion() {
		ProjectService projectService = mock(ProjectService.class);
		DocumentService documentService = mock(DocumentService.class);
		Project created = new Project("eval-proj-1", "RAG Evaluation Corpus", "desc", "N/A",
				"https://example.com/devassist-eval");
		when(projectService.findAll()).thenReturn(List.of());
		when(projectService.create(any(CreateProjectRequest.class))).thenReturn(created);
		when(documentService.ingestText(any(), any())).thenThrow(new RuntimeException("Ollama unreachable"));
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);
		EvalCorpusSeeder seeder = new EvalCorpusSeeder(projectService, documentService, locator, properties,
				new DefaultResourceLoader());

		assertThatCode(() -> seeder.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();
	}
}
