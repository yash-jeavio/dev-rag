package com.devassist.eval;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.devassist.project.CreateProjectRequest;
import com.devassist.project.Project;
import com.devassist.project.ProjectService;

import static org.assertj.core.api.Assertions.assertThat;

class EvalCorpusLocatorTest {

	private final EvalProperties properties = new EvalProperties("RAG Evaluation Corpus",
			"classpath:eval/eval-dataset.json", 4.0, 4.0, 1.0);

	@Test
	void findsTheProjectByConfiguredName() {
		ProjectService projectService = new ProjectService();
		Project created = projectService.create(new CreateProjectRequest("RAG Evaluation Corpus",
				"desc", "N/A", "https://example.com/devassist-eval"));
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);

		Optional<String> found = locator.findProjectId();

		assertThat(found).contains(created.id());
	}

	@Test
	void returnsEmptyWhenNoProjectHasThatName() {
		ProjectService projectService = new ProjectService();
		EvalCorpusLocator locator = new EvalCorpusLocator(projectService, properties);

		assertThat(locator.findProjectId()).isEmpty();
	}
}
